package expo.modules.hbandbridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Pure JVM unit tests: no Android, Expo runtime, AAR, Bluetooth or actual device.
// Run with the production HBandInitializationGate.kt using kotlinc + java.
private fun expectCode(code: String, block: () -> Unit) {
  try {
    block()
    error("Expected exception: $code")
  } catch (error: HBandInitException) {
    check(error.code == code) { "Expected $code, got ${error.code}" }
  }
}

fun main() {
  var passed = 0
  fun test(name: String, block: () -> Unit) {
    block()
    passed++
    println("[PASS] $name")
  }

  test("initial state and getters do not execute vendor action") {
    val gate = HBandInitializationGate()
    check(gate.snapshot().state == "notInitialized")
    check(!gate.snapshot().initCallCompleted)
    check(!gate.snapshot().requiresProcessRestart)
    check(gate.snapshot().lastErrorCode == null)
  }
  test("mark completed only AFTER action returns") {
    val gate = HBandInitializationGate()
    val state = gate.initialize {
      check(gate.snapshot().state == "initializing")
      check(!gate.snapshot().initCallCompleted)
    }
    check(state.state == "initCalled")
    check(state.initCallCompleted)
    check(state.lastErrorCode == null)
  }
  test("repeated success does not execute the action again") {
    val gate = HBandInitializationGate()
    var calls = 0
    gate.initialize { calls++ }
    repeat(10) { gate.initialize { calls++ } }
    check(calls == 1)
  }
  test("exception is recorded; partially initialized SDK is not retried") {
    val gate = HBandInitializationGate()
    var calls = 0
    expectCode("E_SDK_INIT") {
      gate.initialize { calls++; throw IllegalStateException("native init failure") }
    }
    check(gate.snapshot().state == "failed")
    check(!gate.snapshot().initCallCompleted)
    check(gate.snapshot().requiresProcessRestart)
    check(gate.snapshot().lastErrorCode == "E_SDK_INIT")
    expectCode("E_SDK_RESTART_REQUIRED") { gate.initialize { calls++ } }
    check(calls == 1)
    check(gate.snapshot().lastErrorCode == "E_SDK_INIT")
  }
  test("native linkage failure rejects rather than claiming success") {
    val gate = HBandInitializationGate()
    expectCode("E_SDK_LINKAGE") {
      gate.initialize { throw NoClassDefFoundError("example.NativeDependency") }
    }
    check(gate.snapshot().state == "failed")
    check(gate.snapshot().requiresProcessRestart)
  }
  test("SecurityException inside vendor call is not swallowed") {
    val gate = HBandInitializationGate()
    expectCode("E_PERMISSION_DENIED") {
      gate.initialize { throw SecurityException("permission revoked") }
    }
    check(!gate.snapshot().initCallCompleted)
    check(gate.snapshot().requiresProcessRestart)
  }
  test("reentrant initialization is refused without a second action") {
    val gate = HBandInitializationGate()
    var calls = 0
    gate.initialize {
      calls++
      expectCode("E_SDK_INIT_IN_PROGRESS") { gate.initialize { calls++ } }
    }
    check(calls == 1)
    check(gate.snapshot().initCallCompleted)
  }
  test("two concurrent callers execute one action; snapshot stays readable") {
    val gate = HBandInitializationGate()
    val entered = CountDownLatch(1)
    val finish = CountDownLatch(1)
    val done = CountDownLatch(2)
    val calls = AtomicInteger(0)
    val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    val first = Thread {
      try {
        gate.initialize {
          calls.incrementAndGet()
          entered.countDown()
          check(finish.await(5, TimeUnit.SECONDS))
        }
      } catch (error: Throwable) { failures.add(error) }
      finally { done.countDown() }
    }
    first.start()
    check(entered.await(5, TimeUnit.SECONDS))
    check(gate.snapshot().state == "initializing")
    val second = Thread {
      try { gate.initialize { calls.incrementAndGet() } }
      catch (error: Throwable) { failures.add(error) }
      finally { done.countDown() }
    }
    second.start()
    finish.countDown()
    check(done.await(5, TimeUnit.SECONDS))
    check(failures.isEmpty()) { failures.toString() }
    check(calls.get() == 1)
    check(gate.snapshot().initCallCompleted)
  }
  println("$passed JVM state-guard tests passed. NOT an Android or HBand integration test.")
}
