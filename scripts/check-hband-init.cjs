'use strict';
// Read-only source/contract checks. This does NOT compile Kotlin or execute HBand.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8').replace(/^\uFEFF/, '');
const noComments = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const required = (text, pattern, message) => assert.match(text, pattern, message);

try {
  const dependencyCheck = spawnSync(process.execPath, [path.join(root, 'scripts/check-hband-sdk.cjs')], {
    cwd: root, encoding: 'utf8', shell: false,
  });
  if (dependencyCheck.stdout) process.stdout.write(dependencyCheck.stdout);
  if (dependencyCheck.stderr) process.stderr.write(dependencyCheck.stderr);
  if (dependencyCheck.error) throw dependencyCheck.error;
  assert.equal(dependencyCheck.status, 0, 'Step 3 dependency check failed.');

  const base = 'android/src/main/java/expo/modules/hbandbridge/';
  const kotlin = noComments(read(base + 'HBandBridgeModule.kt'));
  const runtime = noComments(read(base + 'HBandSdkRuntime.kt'));
  const gate = noComments(read(base + 'HBandInitializationGate.kt'));
  const ts = noComments(read('src/HBandBridgeModule.ts'));
  const types = noComments(read('src/HBandBridge.types.ts'));
  const web = noComments(read('src/HBandBridgeModule.web.ts'));
  const lock = JSON.parse(read('android/hband-sdk.lock.json'));

  for (const name of ['hello', 'getBridgeInfo', 'getBluetoothState', 'getPermissionsAsync',
    'requestPermissionsAsync', 'getSdkState', 'initializeAsync']) {
    assert.ok(ts.includes(name + '('), 'TypeScript declaration missing: ' + name);
    assert.ok(web.includes(name + '('), 'Web unsupported implementation missing: ' + name);
    assert.ok(new RegExp('(?:Async)?Function\\("' + name + '"\\)').test(kotlin),
      'Kotlin export missing: ' + name);
  }
  required(ts, /initializeAsync\(\):\s*Promise<HBandSdkInfo>/, 'Initialization must be asynchronous.');
  required(ts, /getSdkState\(\):\s*HBandSdkInfo/, 'SDK state type missing.');
  required(kotlin, /Name\("HBandBridge"\)/, 'Native module name changed.');
  required(kotlin, /AsyncFunction\("initializeAsync"\)\s*\{\s*promise:\s*Promise\s*->\s*initializeSdk\(promise\)\s*\}\.runOnQueue\(Queues\.MAIN\)/,
    'Initialize call must be dispatched to the main queue.');
  console.log('[OK] TypeScript / Kotlin / Web method names match (source check only)');

  required(runtime, /VPOperateManager\.getInstance\(\)\.init\(applicationContext\)/,
    'Expected pinned vendor init call is missing.');
  required(runtime, /VPOperateManager\.getInstance\(\)\.setAutoConnectBTBySdk\(false\)/,
    'Expected post-init singleton retrieval/configuration is missing.');
  required(runtime, /private val gate = HBandInitializationGate\(\)/, 'Process-level initialization guard missing.');
  const configuredVersion = runtime.match(/CONFIGURED_PROTOCOL_VERSION\s*=\s*"([^\"]+)"/)?.[1];
  assert.equal(configuredVersion, lock.protocolVersion, 'Protocol metadata and dependency lock differ.');
  assert.equal(configuredVersion, '2.3.81.15', 'This incremental step targets protocol 2.3.81.15.');
  assert.doesNotMatch(runtime, /VPOperateManager\.init\(/, 'init(Context) is NOT static.');
  assert.doesNotMatch(runtime, /getMangerInstance\(/, 'Use the pinned getInstance().init pattern.');
  console.log('[OK] Vendor call pattern, application context and locked protocol version match');

  for (const state of ['notInitialized', 'initializing', 'initCalled', 'failed']) {
    assert.ok(types.includes("'" + state + "'"), 'TypeScript state missing: ' + state);
    assert.ok(gate.includes('"' + state + '"'), 'Kotlin state missing: ' + state);
  }
  required(gate, /@Synchronized/, 'Initialization serialization missing.');
  required(gate, /@Volatile/, 'Snapshot visibility declaration missing.');
  required(gate, /E_SDK_RESTART_REQUIRED/, 'Partial-init failure guard missing.');
  required(kotlin, /"hbandSdkIntegrated"\s+to\s+HBandSdkRuntime\.hasCompletedInitCall\(\)/,
    'Legacy flag must reflect the observed call; do not hard-code success.');
  required(kotlin, /val missing = requiredRuntimePermissions\(\)\.filter/, 'Permission preflight missing.');
  console.log('[OK] Init-state and permission guards are present (not a behavior test)');

  const initBody = kotlin.split('private fun initializeSdk(promise: Promise) {')[1]?.split('private fun requiredRuntimePermissions')[0];
  assert.ok(initBody, 'Initialization method missing.');
  const native = initBody + '\n' + runtime;
  assert.doesNotMatch(native, /\.(?:startScanDevice|connectDevice|confirmDevicePwd|readSportStep|startDetectHeart)\s*\(/,
    'Initialization must not start scanning/connecting/reading health data.');
  assert.doesNotMatch(native, /OnCreate\s*\{|\binit\s*\{/, 'Do not auto-initialize on module load.');
  console.log('[OK] Initializer still has no scan / connect / health-read / automatic-init call');
  console.log('\nSOURCE CHECK PASSED (STATIC ONLY)');
  console.log('No Android compilation, native SDK execution, device connection or performance test was run.');
} catch (error) {
  console.error('\n[FAIL] ' + (error instanceof Error ? error.message : String(error)));
  process.exitCode = 1;
}
