package expo.modules.hbandbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class HBandBridgeModule : Module() {
  @Volatile private var scanController: HBandScanController? = null
  @Volatile private var moduleAlive = true

  override fun definition() = ModuleDefinition {
    Name("HBandBridge")
    Events("onScanStateChanged", "onDeviceFound", "onScanError")

    Function("getScanState") {
      (scanController?.snapshot() ?: HBandScanSnapshot()).toMap()
    }

    AsyncFunction("startScanAsync") { durationSeconds: Double, promise: Promise ->
      startScan(durationSeconds, promise)
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("stopScanAsync") { promise: Promise ->
      try {
        val snapshot = scanController?.stop() ?: HBandScanSnapshot()
        promise.resolve(snapshot.toMap())
      } catch (error: Exception) {
        promise.reject("E_SCAN_STOP", "停止请求执行失败。", error)
      }
    }.runOnQueue(Queues.MAIN)

    // Android Activity paused 时停止：本版只支持前台短时扫描。
    OnActivityEntersBackground {
      scanController?.stopFromLifecycle("background")
    }
    OnActivityDestroys {
      scanController?.stopFromLifecycle("background")
    }
    OnDestroy {
      moduleAlive = false
      scanController?.dispose()
    }


    Function("hello") {
      "Hello world! 👋"
    }

    Function("getBridgeInfo") {
      mapOf<String, Any>(
        "moduleName" to "HBandBridge",
        "bridgeVersion" to "0.1.0",
        "platform" to "android",
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "androidVersion" to Build.VERSION.RELEASE,
        "androidApiLevel" to Build.VERSION.SDK_INT,
        // 兼容旧字段：仅表示本进程中桥观察到初始化调用正常返回。
        // 不表示蓝牙服务、连接和设备协议已实机验证。
        "hbandSdkIntegrated" to HBandSdkRuntime.hasCompletedInitCall()
      )
    }

    Function("getBluetoothState") {
      readBluetoothState()
    }

    // 不执行任何厂商调用，只读取我们自己的状态记录。
    Function("getSdkState") {
      HBandSdkRuntime.getState()
    }

    // 不在 OnCreate/导入模块时自动初始化。宿主授权后显式调用。
    AsyncFunction("initializeAsync") { promise: Promise ->
      initializeSdk(promise)
    }.runOnQueue(Queues.MAIN)

    // 只查询，不弹出权限对话框。
    AsyncFunction("getPermissionsAsync") { promise: Promise ->
      Permissions.getPermissionsWithPermissionsManager(
        appContext.permissions,
        promise,
        *requiredRuntimePermissions()
      )
    }.runOnQueue(Queues.MAIN)

    // 只有宿主主动调用时才请求授权，不在初始化时自动弹窗。
    AsyncFunction("requestPermissionsAsync") { promise: Promise ->
      val activity = appContext.currentActivity
      if (activity == null || activity.isFinishing || activity.isDestroyed) {
        promise.reject(
          "E_NO_ACTIVITY",
          "请在 Android App 的有效页面中请求蓝牙权限。",
          null
        )
      } else {
        Permissions.askForPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          *requiredRuntimePermissions()
        )
      }
    }.runOnQueue(Queues.MAIN)
  }

  private fun startScan(durationSeconds: Double, promise: Promise) {
    try {
      if (!moduleAlive) throw HBandScanException("E_MODULE_DESTROYED", "原生模块已销毁。")
      if (!durationSeconds.isFinite() || durationSeconds % 1.0 != 0.0 ||
        durationSeconds < 2.0 || durationSeconds > 30.0) {
        throw HBandScanException("E_SCAN_DURATION", "扫描时长必须是 2 到 30 秒的整数。")
      }
      val activity = appContext.currentActivity
      if (activity == null || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
        throw HBandScanException("E_APP_NOT_FOREGROUND", "请在前台可交互的 Android 页面发起扫描。")
      }
      assertScanPreconditions()
      val scanner = scanController ?: HBandScanController(
        preflight = { assertScanPreconditions() },
        eventSink = { name, payload ->
          if (moduleAlive) {
            try {
              sendEvent(name, payload)
            } catch (error: Exception) {
              // 不把地址、设备名或原始广播写入本桥日志。
              Log.w("HBandBridge", "Scan event delivery unavailable: $name")
            }
          }
        }
      ).also { scanController = it }
      promise.resolve(scanner.start(durationSeconds.toInt()).toMap())
    } catch (error: HBandScanException) {
      promise.reject(error.code, error.message, error)
    } catch (error: LinkageError) {
      promise.reject("E_SDK_LINKAGE", "扫描依赖无法链接，请检查 Android 构建。", error)
    } catch (error: Exception) {
      promise.reject("E_SCAN_START", "扫描请求执行失败。", error)
    }
  }

  private fun assertScanPreconditions() {
    val context = appContext.reactContext
      ?: throw HBandScanException("E_NO_CONTEXT", "Android 原生运行环境尚未可用。")
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      throw HBandScanException("E_BLE_UNSUPPORTED", "手机未声明支持 BLE。")
    }
    val missing = requiredRuntimePermissions().filter { !hasPermission(context, it) }
    if (missing.isNotEmpty()) {
      throw HBandScanException("E_PERMISSION_DENIED", "缺少蓝牙扫描所需权限：${missing.joinToString()}")
    }
    if (!HBandSdkRuntime.hasCompletedInitCall()) {
      throw HBandScanException("E_SDK_NOT_INITIALIZED", "请先获得权限并调用 initializeAsync()。")
    }
    if (readAdapterState(context) != "poweredOn") {
      throw HBandScanException("E_BLUETOOTH_OFF", "手机蓝牙尚未处于开启状态。")
    }
    // Android 6–11 的扫描除了授权，通常还依赖系统定位开关。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      val location = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
      val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        location?.isLocationEnabled == true
      } else {
        location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
          location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
      }
      if (!enabled) throw HBandScanException("E_LOCATION_DISABLED", "旧版 Android 请打开系统定位开关后扫描。")
    }
  }

  private fun initializeSdk(promise: Promise) {
    val context = appContext.reactContext
    if (context == null) {
      promise.reject("E_NO_CONTEXT", "Android 原生运行环境尚未可用。", null)
      return
    }

    // 保守的桥接策略：显式授权后才启动厂商初始化。
    // 这些前置检查失败时没有调用 SDK，授权/条件修复后可以再次尝试。
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      promise.reject("E_BLE_UNSUPPORTED", "此设备未声明支持 BLE。", null)
      return
    }

    val missing = requiredRuntimePermissions().filter { !hasPermission(context, it) }
    if (missing.isNotEmpty()) {
      promise.reject(
        "E_PERMISSION_DENIED",
        "请先调用 requestPermissionsAsync() 并获得授权。缺少：${missing.joinToString()}",
        null
      )
      return
    }

    try {
      // 不要求此时蓝牙开关已开启；后面的扫描接口还要另行检查。
      promise.resolve(HBandSdkRuntime.initialize(context.applicationContext))
    } catch (error: HBandInitException) {
      promise.reject(error.code, error.message, error)
    } catch (error: LinkageError) {
      // 捕获加载桥接 Runtime 类本身就失败的情况；某些进程级崩溃仍需 logcat 排查。
      promise.reject("E_SDK_LINKAGE", "无法加载 HBand 桥接原生类或依赖。", error)
    } catch (error: Exception) {
      promise.reject("E_SDK_INIT", "HBand 初始化请求执行失败。", error)
    }
  }

  // 面向现代 Expo 宿主（targetSdkVersion >= 31）。
  // 分支判断的是手机实际运行的 Android 版本。
  private fun requiredRuntimePermissions(): Array<String> {
    return when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
      )
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION
      )
      else -> emptyArray()
    }
  }

  private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) ==
      PackageManager.PERMISSION_GRANTED
  }

  private fun readBluetoothState(): Map<String, Any> {
    val context = appContext.reactContext ?: throw Exceptions.ReactContextLost()
    val supported = context.packageManager.hasSystemFeature(
      PackageManager.FEATURE_BLUETOOTH_LE
    )

    val state = when {
      !supported -> "unsupported"

      // 本模块的保守策略：未获 CONNECT 授权时，不读取适配器状态。
      // 返回 unknown，而不是把“未授权”伪装成“蓝牙关闭”。
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT) -> "unknown"

      else -> readAdapterState(context)
    }

    val missing = requiredRuntimePermissions().filter {
      !hasPermission(context, it)
    }

    return mapOf(
      "bleSupported" to supported,
      "state" to state,
      "missingPermissions" to missing
    )
  }

  @SuppressLint("MissingPermission")
  private fun readAdapterState(context: Context): String {
    // 调用前已检查授权；仍捕获用户同时撤销权限等情况。
    return try {
      val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
        as? BluetoothManager

      when (manager?.adapter?.state) {
        BluetoothAdapter.STATE_ON -> "poweredOn"
        BluetoothAdapter.STATE_OFF -> "poweredOff"
        BluetoothAdapter.STATE_TURNING_ON -> "turningOn"
        BluetoothAdapter.STATE_TURNING_OFF -> "turningOff"
        else -> "unknown"
      }
    } catch (_: SecurityException) {
      "unknown"
    }
  }
}
