package expo.modules.hbandbridge

/** 仅记录“桥观察到的初始化调用”，不是设备连接或服务就绪状态。 */
internal data class HBandInitSnapshot(
  val state: String = "notInitialized",
  val lastErrorCode: String? = null,
  val lastErrorMessage: String? = null
) {
  val initCallCompleted: Boolean get() = state == "initCalled"
  val requiresProcessRestart: Boolean get() = state == "failed"
}

internal class HBandInitException(
  val code: String,
  message: String,
  cause: Throwable? = null
) : Exception(message, cause)

/**
 * 不依赖 Android 的状态保护器，可独立做 JVM 单元测试。
 * 同步锁防止重复执行；volatile 快照让查询不用等待初始化持有的锁。
 * 厂商初始化一旦抛错，不在同一进程盲目重试，避免部分静态状态残留。
 */
internal class HBandInitializationGate {
  @Volatile private var current = HBandInitSnapshot()

  fun snapshot(): HBandInitSnapshot = current

  @Synchronized
  fun initialize(action: () -> Unit): HBandInitSnapshot {
    when (current.state) {
      "initCalled" -> return current
      "initializing" -> throw HBandInitException(
        "E_SDK_INIT_IN_PROGRESS", "SDK 初始化正在执行，请不要重复调用。"
      )
      "failed" -> throw HBandInitException(
        "E_SDK_RESTART_REQUIRED",
        "上次原生初始化失败。修复原因并结束 App 进程后重新启动，不要在当前进程继续重试。"
      )
    }

    current = HBandInitSnapshot(state = "initializing")
    try {
      action()
      // 只有 action 中全部同步调用正常返回，才更新此状态。
      current = HBandInitSnapshot(state = "initCalled")
      return current
    } catch (error: SecurityException) {
      fail("E_PERMISSION_DENIED", "厂商 SDK 调用时权限不足或已被撤销。", error)
    } catch (error: LinkageError) {
      fail("E_SDK_LINKAGE", "HBand 原生类或依赖无法链接，请检查 Android 构建与运行日志。", error)
    } catch (error: Exception) {
      fail("E_SDK_INIT", "HBand 初始化调用失败，请检查 Android 原生日志。", error)
    }
  }

  private fun fail(code: String, message: String, cause: Throwable): Nothing {
    current = HBandInitSnapshot(
      state = "failed", lastErrorCode = code, lastErrorMessage = message
    )
    throw HBandInitException(code, message, cause)
  }
}
