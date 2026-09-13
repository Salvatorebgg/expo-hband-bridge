package expo.modules.hbandbridge

private class FakeTime : HBandScanScheduler {
  private data class Task(val at: Long, val block: () -> Unit, var canceled: Boolean = false)
  var now = 0L
  private val tasks = mutableListOf<Task>()
  override fun after(delayMs: Long, block: () -> Unit): HBandScanCancellation {
    val task = Task(now + delayMs, block)
    tasks += task
    return HBandScanCancellation { task.canceled = true }
  }
  fun advance(ms: Long) {
    val target = now + ms
    while (true) {
      val task = tasks.filter { !it.canceled && it.at <= target }.minByOrNull { it.at } ?: break
      tasks.remove(task)
      now = task.at
      task.block()
    }
    now = target
  }
}
private class FakeBackend : HBandScanBackend {
  val callbacks = mutableListOf<HBandScanCallback>()
  var starts = 0
  var stops = 0
  var throwStart: Throwable? = null
  var throwStop: Throwable? = null
  var acknowledgeStop = false
  override fun start(durationSeconds: Int, callback: HBandScanCallback) {
    starts++
    callbacks += callback
    throwStart?.let { throw it }
  }
  override fun stop() {
    stops++
    throwStop?.let { throw it }
    if (acknowledgeStop) callbacks.last().stopped(true)
  }
}
private class Fixture(ownership: HBandScanOwnership = HBandScanOwnership()) {
  val backend = FakeBackend()
  val time = FakeTime()
  val events = mutableListOf<Pair<String, Map<String, Any?>>>()
  val engine = HBandScanEngine(backend, time, ownership, { time.now }) { name, data -> events += name to data }
  fun callback() = backend.callbacks.last()
  fun start(seconds: Int = 10) { engine.start(seconds) }
}
private fun expectCode(code: String, action: () -> Unit) {
  try { action(); error("Expected $code") } catch (error: HBandScanException) { check(error.code == code) { "${error.code} != $code" } }
}
private var passed = 0
private fun test(name: String, body: () -> Unit) { body(); passed++; println("PASS $name") }

fun main() {
  test("idle does not call SDK") {
    val f = Fixture(); check(f.engine.snapshot().state == "idle"); check(f.backend.starts == 0)
  }
  test("duration bounds reject before SDK") {
    val f = Fixture()
    for (s in listOf(-1, 0, 1, 31, Int.MAX_VALUE)) expectCode("E_SCAN_DURATION") { f.start(s) }
    check(f.backend.starts == 0)
  }
  test("request accepted is not start confirmed") {
    val f = Fixture(); f.start(); check(f.engine.snapshot().state == "starting")
    check(!f.engine.snapshot().sdkStartObserved)
    f.callback().started(); check(f.engine.snapshot().state == "scanning"); check(f.engine.snapshot().sdkStartObserved)
  }
  test("a duplicate start never cancels original scan") {
    val f = Fixture(); f.start(); val id = f.engine.snapshot().scanId
    expectCode("E_SCAN_IN_PROGRESS") { f.start() }; check(f.backend.starts == 1)
    check(f.engine.snapshot().scanId == id)
  }
  test("discovery de-duplicates case insensitive address; nullable name") {
    val f = Fixture(); f.start(); f.callback().started()
    f.callback().found("aa:bb:cc:dd:ee:01", null, -55)
    f.callback().found("AA:BB:CC:DD:EE:01", "Late name", -53)
    check(f.engine.snapshot().deviceCount == 1)
    val data = f.events.single { it.first == "onDeviceFound" }.second
    check(data["name"] == null && data["rssi"] == -55 && data["source"] == "hbandSdkScan")
  }
  test("invalid addresses are ignored") {
    val f = Fixture(); f.start(); f.callback().found("", "", -99); f.callback().found("not an address", null, -20)
    check(f.engine.snapshot().deviceCount == 0)
  }
  test("bounded result memory") {
    val f = Fixture(); f.start()
    for (i in 0..260) f.callback().found("AA:BB:CC:DD:%02X:%02X".format(i / 256, i % 256), "D", -60)
    check(f.engine.snapshot().deviceCount == 256 && f.engine.snapshot().resultLimitReached)
  }
  test("stopped callback ends scan and cancels timeout") {
    val f = Fixture(); f.start(); f.callback().started(); f.callback().stopped(false)
    check(f.engine.snapshot().state == "stopped" && f.engine.snapshot().stopConfirmed)
    f.time.advance(20000); check(f.backend.stops == 0)
  }
  test("stop request must await SDK acknowledgment") {
    val f = Fixture(); f.start(); f.engine.stop("requested")
    check(f.engine.snapshot().state == "stopping" && !f.engine.snapshot().stopConfirmed)
    f.engine.stop("requested"); check(f.backend.stops == 1)
    f.callback().stopped(true); check(f.engine.snapshot().state == "stopped")
    check(f.engine.snapshot().stopReason == "requested")
  }
  test("SDK cancellation is not successful connection") {
    val f = Fixture(); f.start(); f.callback().stopped(true)
    check(f.engine.snapshot().stopReason == "sdkCanceled"); check(f.engine.snapshot().stopConfirmed)
  }
  test("deadline stops scan; synchronous stop callback accepted") {
    val f = Fixture(); f.backend.acknowledgeStop = true; f.start(2); f.callback().started()
    f.time.advance(3500); check(f.backend.stops == 1)
    check(f.engine.snapshot().state == "stopped" && f.engine.snapshot().stopReason == "timeout")
  }
  test("missing start callback produces failure, not success") {
    val f = Fixture(); f.backend.acknowledgeStop = true; f.start(2); f.time.advance(3500)
    check(f.engine.snapshot().state == "failed"); check(f.engine.snapshot().lastErrorCode == "E_SCAN_START_NOT_CONFIRMED")
    check(f.engine.snapshot().stopConfirmed && !f.engine.snapshot().requiresProcessRestart)
  }
  test("unacknowledged stop poisons process ownership") {
    val owner = HBandScanOwnership(); val f = Fixture(owner)
    f.start(); f.callback().started(); f.engine.stop("requested"); f.time.advance(2500)
    check(f.engine.snapshot().state == "failed" && f.engine.snapshot().requiresProcessRestart)
    check(!f.engine.snapshot().stopConfirmed)
    expectCode("E_SCAN_RESTART_REQUIRED") { Fixture(owner).start() }
  }
  test("late old callback does not contaminate new session") {
    val f = Fixture(); f.start(); val old = f.callback(); old.stopped(false); f.start(); val current = f.engine.snapshot().scanId
    old.failed("OLD_ERROR", "old callback"); old.started(); old.found("AA:BB:CC:DD:EE:FF", "old", -30); old.stopped(true)
    check(f.engine.snapshot().scanId == current && f.engine.snapshot().state == "starting")
    check(f.engine.snapshot().deviceCount == 0)
  }
  test("found after stopping is discarded") {
    val f = Fixture(); f.start(); f.engine.stop("background")
    f.callback().found("AA:BB:CC:DD:EE:FF", "late", -20); check(f.engine.snapshot().deviceCount == 0)
  }
  test("process ownership prevents competing scanner modules") {
    val owner = HBandScanOwnership(); val a = Fixture(owner); val b = Fixture(owner)
    a.start(); expectCode("E_SCAN_IN_PROGRESS") { b.start() }; check(b.backend.starts == 0)
    a.callback().stopped(false); b.start(); check(b.backend.starts == 1)
  }
  test("start exception rejects and requests cleanup") {
    val f = Fixture(); f.backend.throwStart = IllegalStateException("fake"); f.backend.acknowledgeStop = true
    expectCode("E_SCAN_START") { f.start() }
    check(f.backend.stops == 1 && f.engine.snapshot().state == "failed")
    check(f.engine.snapshot().stopConfirmed)
  }
  test("linkage error is reported with cleanup, not crash in engine") {
    val f = Fixture(); f.backend.throwStart = NoClassDefFoundError("fake")
    expectCode("E_SDK_LINKAGE") { f.start() }; f.time.advance(2500)
    check(f.engine.snapshot().requiresProcessRestart)
  }
  test("stop exception blocks further attempts") {
    val f = Fixture(); f.start(); f.backend.throwStop = SecurityException("revoked")
    f.engine.stop("permissionRevoked")
    check(f.engine.snapshot().requiresProcessRestart && f.engine.snapshot().state == "failed")
  }
  test("permission loss error emits event and awaits cleanup") {
    val f = Fixture(); f.start(); f.backend.acknowledgeStop = true
    f.engine.abort("E_PERMISSION_DENIED", "Permission revoked", "permissionRevoked")
    check(f.engine.snapshot().state == "failed")
    check(f.events.count { it.first == "onScanError" } == 1)
  }
  test("dispose requests stop only once and emits nothing further") {
    val f = Fixture(); f.start(); val events = f.events.size
    f.engine.dispose(); f.engine.dispose(); f.callback().stopped(true)
    check(f.backend.stops == 1); check(f.events.size == events)
    expectCode("E_MODULE_DESTROYED") { f.start() }
  }
  println("$passed pure JVM scan-engine tests passed. No Android/HBand/BLE execution.")
}
