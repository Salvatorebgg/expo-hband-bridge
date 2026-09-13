package expo.modules.hbandbridge

import android.os.Handler
import android.os.Looper
import com.inuker.bluetooth.library.search.SearchResult
import com.inuker.bluetooth.library.search.response.SearchResponse
import com.veepoo.protocol.VPOperateManager
import java.lang.ref.WeakReference

/** Handler 计时器只在扫描会话存活期间存在，本桥不新增 Service，厂商自身服务沿用既有配置。 */
private class MainScanScheduler(private val handler: Handler) : HBandScanScheduler {
  override fun after(delayMs: Long, block: () -> Unit): HBandScanCancellation {
    val task = Runnable { block() }
    check(handler.postDelayed(task, delayMs)) { "Android main looper is unavailable" }
    return HBandScanCancellation { handler.removeCallbacks(task) }
  }
}

/** 对上传 AAR 中真实方法签名的薄适配，不自己重写 BLE 协议。 */
private class VendorScanBackend(
  private val handler: Handler
) : HBandScanBackend {
  override fun start(durationSeconds: Int, callback: HBandScanCallback) {
    // 防止厂商迟迟持有 SearchResponse 导致已销毁的 Expo module 被持有。
    val target = WeakReference(callback)
    val dispatcher = handler

    VPOperateManager.getInstance().startScanDevice(durationSeconds, object : SearchResponse {
      override fun onSearchStarted() {
        dispatcher.post { target.get()?.started() }
      }
      // 注意厂商真实拼写为 Founded，不是 Found。
      override fun onDeviceFounded(device: SearchResult?) {
        if (device == null) return
        try {
          val address = device.address ?: return
          val name = device.name
          val rssi = device.rssi
          // 不把 SDK 可变对象跨到 JS，也不导出 scanRecord 原始广播。
          dispatcher.post { target.get()?.found(address, name, rssi) }
        } catch (error: SecurityException) {
          dispatcher.post { target.get()?.failed("E_PERMISSION_DENIED", "读取广播信息时授权已被撤销。") }
        } catch (error: LinkageError) {
          dispatcher.post { target.get()?.failed("E_SDK_LINKAGE", "读取广播信息时原生依赖不可用。") }
        } catch (error: Exception) {
          dispatcher.post { target.get()?.failed("E_SCAN_RESULT", "读取厂商扫描结果失败。") }
        }
      }
      override fun onSearchStopped() {
        dispatcher.post { target.get()?.stopped(false) }
      }
      override fun onSearchCanceled() {
        dispatcher.post { target.get()?.stopped(true) }
      }
    })
  }
  override fun stop() { VPOperateManager.getInstance().stopScanDevice() }
}

/**
 * 所有操作串行放到 Android main Handler。
 * 不自动申请权限，不自动初始化 SDK，不自动重连/重启扫描。
 */
internal class HBandScanController(
  private var preflight: (() -> Unit)?,
  private var eventSink: ((String, Map<String, Any?>) -> Unit)?
) {
  private val handler = Handler(Looper.getMainLooper())
  private val scheduler = MainScanScheduler(handler)
  private var healthCheck: HBandScanCancellation? = null
  private var disposed = false
  private val backend: HBandScanBackend = VendorScanBackend(handler)
  private val engine: HBandScanEngine = HBandScanEngine(
    backend, scheduler, HBandProcessScanOwnership.lease, System::currentTimeMillis
  ) { name, data ->
    if (name == "onScanStateChanged" && data["state"] in setOf("stopping", "stopped", "failed")) {
      healthCheck?.cancel(); healthCheck = null
    }
    eventSink?.invoke(name, data)
  }

  fun snapshot(): HBandScanSnapshot = engine.snapshot()

  fun start(durationSeconds: Int): HBandScanSnapshot {
    checkMainThread()
    if (disposed) throw HBandScanException("E_MODULE_DESTROYED", "原生模块已销毁。")
    preflight?.invoke() ?: throw HBandScanException("E_MODULE_DESTROYED", "扫描前置检查已释放。")
    val result = engine.start(durationSeconds)
    scheduleHealthCheck()
    return result
  }

  fun stop(reason: String = "requested"): HBandScanSnapshot {
    checkMainThread()
    healthCheck?.cancel(); healthCheck = null
    return engine.stop(reason)
  }

  fun stopFromLifecycle(reason: String) {
    onMain { if (!disposed) stop(reason) }
  }

  fun dispose() {
    onMain {
      if (!disposed) {
        disposed = true
        eventSink = null
        preflight = null
        healthCheck?.cancel(); healthCheck = null
        // 仍保留状态机停止确认计时器，防止新 module 抢用未停止的厂商扫描器。
        engine.dispose()
      }
    }
  }

  private fun scheduleHealthCheck() {
    healthCheck?.cancel()
    if (!engine.isActive() || engine.snapshot().state == "stopping" || disposed) return
    healthCheck = scheduler.after(1000L) {
      if (!engine.isActive() || engine.snapshot().state == "stopping" || disposed) return@after
      try {
        preflight?.invoke()
      } catch (error: HBandScanException) {
        val reason = when (error.code) {
          "E_BLUETOOTH_OFF" -> "bluetoothOff"
          "E_PERMISSION_DENIED" -> "permissionRevoked"
          "E_LOCATION_DISABLED" -> "locationDisabled"
          else -> "error"
        }
        engine.abort(error.code, error.message ?: "扫描条件失效。", reason)
        return@after
      } catch (error: LinkageError) {
        engine.abort("E_SDK_LINKAGE", "扫描检查时原生依赖无法加载。", "error")
        return@after
      } catch (error: Exception) {
        engine.abort("E_SCAN_PRECONDITION", "扫描状态检查失败。", "error")
        return@after
      }
      scheduleHealthCheck()
    }
  }
  private fun onMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
  }
  private fun checkMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Scan controller requires Android main thread" }
  }
}
