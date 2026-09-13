package expo.modules.hbandbridge

import android.content.Context
import android.os.Looper
import com.veepoo.protocol.VPOperateManager

/**
 * 同一 App 进程内共享一次初始化记录，不保存 Activity/ReactContext。
 * 仅有 getState() 不会创建厂商实例，也不扫描、不连接。
 * 宿主应统一通过本桥管理 SDK，不要另行调用厂商 release()/init()。
 */
internal object HBandSdkRuntime {
  // 来自第 3 步依赖锁：这是配置版本，不是“已经实机加载”的证明。
  const val CONFIGURED_PROTOCOL_VERSION = "2.3.81.15"
  private val gate = HBandInitializationGate()

  fun hasCompletedInitCall(): Boolean = gate.snapshot().initCallCompleted

  fun getState(): Map<String, Any?> {
    val snapshot = gate.snapshot()
    return mapOf(
      "state" to snapshot.state,
      "initCallCompleted" to snapshot.initCallCompleted,
      "configuredProtocolVersion" to CONFIGURED_PROTOCOL_VERSION,
      "requiresProcessRestart" to snapshot.requiresProcessRestart,
      "lastErrorCode" to snapshot.lastErrorCode,
      "lastErrorMessage" to snapshot.lastErrorMessage
    )
  }

  fun initialize(context: Context): Map<String, Any?> {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      throw HBandInitException(
        "E_WRONG_THREAD", "HBand 初始化必须由桥安排到 Android 主线程。"
      )
    }

    gate.initialize {
      val applicationContext = context.applicationContext

      // 以下 API 已在上传的 vpprotocol-2.3.81.15.aar 中核对。
      // init(Context) 是实例方法，不是 VPOperateManager.init(...) 静态方法。
      VPOperateManager.getInstance().init(applicationContext)

      // 这版 SDK 的 init 可能替换单例，初始化后重新获取实例。
      // 与厂商 Demo 一致：关闭其自动连接 BT 功能；这不是 BLE 扫描开关。
      VPOperateManager.getInstance().setAutoConnectBTBySdk(false)
    }

    // init(Context) 返回 void；这里只表明上述调用已返回。
    // 不代表 BluetoothService 完成绑定、设备连接、认证或健康数据读取成功。
    return getState()
  }
}
