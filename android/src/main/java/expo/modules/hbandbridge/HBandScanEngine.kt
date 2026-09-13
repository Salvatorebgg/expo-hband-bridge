package expo.modules.hbandbridge

import java.util.Locale
import java.util.UUID

/** 只描述本桥观察到的扫描会话，不表示已连接或型号兼容。 */
internal data class HBandScanSnapshot(
  val scanId: String? = null,
  val state: String = "idle",
  val durationSeconds: Int? = null,
  val sdkStartObserved: Boolean = false,
  val deviceCount: Int = 0,
  val resultLimitReached: Boolean = false,
  val stopReason: String? = null,
  val stopConfirmed: Boolean = false,
  val requiresProcessRestart: Boolean = false,
  val lastErrorCode: String? = null,
  val lastErrorMessage: String? = null
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "scanId" to scanId, "state" to state, "durationSeconds" to durationSeconds,
    "sdkStartObserved" to sdkStartObserved, "deviceCount" to deviceCount,
    "resultLimitReached" to resultLimitReached, "stopReason" to stopReason,
    "stopConfirmed" to stopConfirmed, "requiresProcessRestart" to requiresProcessRestart,
    "lastErrorCode" to lastErrorCode, "lastErrorMessage" to lastErrorMessage
  )
}
internal class HBandScanException(val code: String, message: String, cause: Throwable? = null)
  : Exception(message, cause)

internal fun interface HBandScanCancellation { fun cancel() }
internal interface HBandScanScheduler {
  fun after(delayMs: Long, block: () -> Unit): HBandScanCancellation
}
internal interface HBandScanCallback {
  fun started()
  fun found(address: String, name: String?, rssi: Int)
  fun stopped(canceled: Boolean)
  fun failed(code: String, message: String)
}
internal interface HBandScanBackend {
  fun start(durationSeconds: Int, callback: HBandScanCallback)
  fun stop()
}

/** 厂商扫描器是进程级资源：不同 Expo module 实例不能同时占用。 */
internal class HBandScanOwnership {
  private var owner: String? = null
  private var poisoned = false

  @Synchronized fun acquire(id: String) {
    if (poisoned) throw HBandScanException(
      "E_SCAN_RESTART_REQUIRED", "上次扫描未确认停止，请结束 App 进程并重新启动后再测。"
    )
    if (owner != null) throw HBandScanException("E_SCAN_IN_PROGRESS", "已有扫描正在运行或停止中。")
    owner = id
  }
  @Synchronized fun release(id: String) {
    if (owner == id && !poisoned) owner = null
  }
  @Synchronized fun poison(id: String) { if (owner == id) poisoned = true }
}
internal object HBandProcessScanOwnership { val lease = HBandScanOwnership() }

/**
 * 可独立测试的扫描状态机，不导入 Android / Expo / HBand 类。
 * 所有修改、计时器和 SDK 回调必须由 adapter 串行分发到主线程。
 * snapshot 是 volatile 不可变对象，允许同步 JS 查询线程读取。
 */
internal class HBandScanEngine(
  private val backend: HBandScanBackend,
  private val scheduler: HBandScanScheduler,
  private val ownership: HBandScanOwnership,
  private val observedAt: () -> Long,
  private val emit: (String, Map<String, Any?>) -> Unit
) {
  @Volatile private var current = HBandScanSnapshot()
  private var disposed = false
  private var eventsEnabled = true
  private var deadline: HBandScanCancellation? = null
  private var stopDeadline: HBandScanCancellation? = null
  private var activeCallback: HBandScanCallback? = null
  private val addresses = HashSet<String>()

  fun snapshot(): HBandScanSnapshot = current
  private fun active(id: String?): Boolean = id != null && id == current.scanId &&
    current.state in setOf("starting", "scanning", "stopping")
  fun isActive(): Boolean = active(current.scanId)

  fun start(durationSeconds: Int): HBandScanSnapshot {
    if (disposed) throw HBandScanException("E_MODULE_DESTROYED", "原生模块已销毁。")
    if (durationSeconds !in 2..30) throw HBandScanException(
      "E_SCAN_DURATION", "扫描时长必须是 2 到 30 秒的整数。"
    )
    if (isActive()) throw HBandScanException("E_SCAN_IN_PROGRESS", "请等待当前扫描停止后再开始。")
    if (current.requiresProcessRestart) throw HBandScanException(
      "E_SCAN_RESTART_REQUIRED", "扫描停止未被 SDK 确认，请重启 App 进程。"
    )
    val id = UUID.randomUUID().toString()
    ownership.acquire(id)
    addresses.clear()
    current = HBandScanSnapshot(scanId = id, state = "starting", durationSeconds = durationSeconds)
    stateEvent()

    val callback = object : HBandScanCallback {
      override fun started() {
        if (!active(id) || current.state != "starting") return
        current = current.copy(state = "scanning", sdkStartObserved = true)
        stateEvent()
      }
      override fun found(address: String, name: String?, rssi: Int) {
        if (!active(id) || current.state == "stopping" || disposed) return
        val normalized = address.uppercase(Locale.ROOT)
        if (!Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").matches(normalized)) return
        if (normalized in addresses) return
        if (addresses.size >= 256) {
          if (!current.resultLimitReached) {
            current = current.copy(resultLimitReached = true)
            stateEvent()
          }
          return
        }
        addresses += normalized
        current = current.copy(deviceCount = addresses.size)
        if (eventsEnabled) emit("onDeviceFound", mapOf(
          "scanId" to id, "address" to normalized,
          "name" to name?.takeIf { it.isNotBlank() }, "rssi" to rssi,
          "observedAt" to observedAt(), "source" to "hbandSdkScan"
        ))
      }
      override fun failed(code: String, message: String) {
        if (active(id) && current.state != "stopping") abort(code, message, "error")
      }
      override fun stopped(canceled: Boolean) {
        if (!active(id)) return
        cancelTimers()
        activeCallback = null
        ownership.release(id)
        current = current.copy(
          state = if (current.lastErrorCode == null) "stopped" else "failed",
          stopReason = current.stopReason ?: if (canceled) "sdkCanceled" else "sdkStopped",
          stopConfirmed = true
        )
        stateEvent()
        if (current.lastErrorCode != null) errorEvent()
      }
    }
    // Keep the callback alive during the session; vendor adapter holds only a WeakReference.
    activeCallback = callback
    deadline = scheduler.after(durationSeconds * 1000L + 1500L) {
      if (active(id) && current.state != "stopping") {
        if (current.sdkStartObserved) stop("timeout")
        else abort("E_SCAN_START_NOT_CONFIRMED", "未收到 SDK 扫描开始回调，已请求停止。", "error")
      }
    }
    try {
      backend.start(durationSeconds, callback)
    } catch (error: SecurityException) {
      startFailed("E_PERMISSION_DENIED", "调用 SDK 扫描时权限不足或被撤销。", error)
    } catch (error: LinkageError) {
      startFailed("E_SDK_LINKAGE", "扫描所需原生类或依赖无法加载。", error)
    } catch (error: Exception) {
      startFailed("E_SCAN_START", "SDK 扫描调用抛出异常，请检查原生日志。", error)
    }
    return current
  }

  /** 返回请求状态；只有 SDK 的终止回调才设置 stopConfirmed。 */
  fun stop(reason: String = "requested"): HBandScanSnapshot {
    val id = current.scanId
    if (!active(id) || current.state == "stopping") return current
    deadline?.cancel(); deadline = null
    current = current.copy(state = "stopping", stopReason = current.stopReason ?: reason)
    stateEvent()
    stopDeadline = scheduler.after(2500L) {
      if (active(id)) unknownStop("E_SCAN_STOP_TIMEOUT", "SDK 未确认扫描停止，请重启 App 进程后再试。")
    }
    try {
      backend.stop()
    } catch (error: SecurityException) {
      if (active(id)) unknownStop("E_PERMISSION_DENIED", "停止扫描时权限已被撤销，停止状态不确定。")
    } catch (error: LinkageError) {
      if (active(id)) unknownStop("E_SDK_LINKAGE", "停止扫描时原生依赖错误，停止状态不确定。")
    } catch (error: Exception) {
      if (active(id)) unknownStop("E_SCAN_STOP", "SDK 停止调用失败，停止状态不确定。")
    }
    return current
  }

  fun abort(code: String, message: String, reason: String): HBandScanSnapshot {
    if (!isActive() || current.state == "stopping") return current
    current = current.copy(lastErrorCode = code, lastErrorMessage = message, stopReason = reason)
    return stop(reason)
  }

  fun dispose() {
    if (disposed) return
    disposed = true
    eventsEnabled = false
    stop("moduleDestroyed")
  }

  private fun startFailed(code: String, message: String, cause: Throwable): Nothing {
    abort(code, message, "error")
    throw HBandScanException(code, message, cause)
  }
  private fun unknownStop(code: String, message: String) {
    val id = current.scanId ?: return
    cancelTimers()
    activeCallback = null
    ownership.poison(id)
    current = current.copy(
      state = "failed", stopConfirmed = false, requiresProcessRestart = true,
      lastErrorCode = code, lastErrorMessage = message
    )
    stateEvent()
    errorEvent()
  }
  private fun cancelTimers() {
    deadline?.cancel(); deadline = null
    stopDeadline?.cancel(); stopDeadline = null
  }
  private fun stateEvent() { if (eventsEnabled) emit("onScanStateChanged", current.toMap()) }
  private fun errorEvent() {
    if (eventsEnabled) emit("onScanError", mapOf(
      "scanId" to current.scanId, "code" to current.lastErrorCode,
      "message" to current.lastErrorMessage,
      "requiresProcessRestart" to current.requiresProcessRestart
    ))
  }
}
