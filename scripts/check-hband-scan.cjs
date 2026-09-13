'use strict';
// Read-only source and dependency checks, NOT Android compilation or BLE execution.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8').replace(/^\uFEFF/, '');
const withoutComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');

function checkSources(base = root) {
  const load = (f) => withoutComments(fs.readFileSync(path.join(base, f), 'utf8'));
  const nativeDir = 'android/src/main/java/expo/modules/hbandbridge/';
  const module = load(nativeDir + 'HBandBridgeModule.kt');
  const controller = load(nativeDir + 'HBandScanController.kt');
  const engine = load(nativeDir + 'HBandScanEngine.kt');
  const ts = load('src/HBandBridgeModule.ts');
  const web = load('src/HBandBridgeModule.web.ts');
  const types = load('src/HBandBridge.types.ts');
  for (const name of ['getScanState', 'startScanAsync', 'stopScanAsync']) {
    assert.ok(ts.includes(name + '('), 'TS method missing: ' + name);
    assert.ok(web.includes(name + '('), 'Web rejection missing: ' + name);
    assert.match(module, new RegExp('(?:Async)?Function\\("' + name + '"\\)'), 'Kotlin export missing: ' + name);
  }
  assert.match(ts, /extends NativeModule<HBandBridgeEvents>/);
  for (const event of ['onScanStateChanged', 'onDeviceFound', 'onScanError']) {
    assert.ok(types.includes(event + '('), 'TS event missing: ' + event);
    assert.ok(module.includes('"' + event + '"'), 'Native event declaration missing: ' + event);
    assert.ok(engine.includes('"' + event + '"'), 'Native event emission missing: ' + event);
  }
  console.log('[OK] Scan methods and three typed events match (source check only)');

  assert.match(controller, /import com\.inuker\.bluetooth\.library\.search\.response\.SearchResponse/);
  for (const callback of ['onSearchStarted', 'onDeviceFounded', 'onSearchStopped', 'onSearchCanceled']) {
    assert.match(controller, new RegExp('override fun ' + callback + '\\('), 'SDK callback missing: ' + callback);
  }
  assert.match(controller, /VPOperateManager\.getInstance\(\)\.startScanDevice\(durationSeconds, object : SearchResponse/);
  assert.match(controller, /VPOperateManager\.getInstance\(\)\.stopScanDevice\(\)/);
  assert.match(module, /durationSeconds % 1\.0 != 0\.0/);
  assert.match(engine, /durationSeconds !in 2\.\.30/);
  console.log('[OK] Pinned HBand callback signatures and duration units are represented');

  for (const key of ['E_PERMISSION_DENIED', 'E_BLUETOOTH_OFF', 'E_LOCATION_DISABLED', 'E_SDK_NOT_INITIALIZED', 'E_APP_NOT_FOREGROUND']) {
    assert.ok(module.includes('"' + key + '"'), 'Preflight error missing: ' + key);
  }
  for (const key of ['E_SCAN_RESTART_REQUIRED', 'E_SCAN_STOP_TIMEOUT', 'E_SCAN_START_NOT_CONFIRMED']) {
    assert.ok(engine.includes('"' + key + '"'), 'State protection missing: ' + key);
  }
  assert.match(controller, /WeakReference\(callback\)/);
  assert.match(controller, /Handler\(Looper\.getMainLooper\(\)\)/);
  assert.match(module, /OnActivityEntersBackground\s*\{/);
  assert.match(module, /OnDestroy\s*\{/);
  assert.match(module, /scanController\?\.dispose\(\)/);
  assert.match(engine, /addresses\.size >= 256/);
  assert.match(engine, /stopConfirmed = true/);
  assert.match(engine, /requiresProcessRestart = true/);
  console.log('[OK] Permission, lifecycle, timeout and session guards are present (not a behavior test)');
  const all = [module, controller, engine].join('\n');
  assert.doesNotMatch(all, /\.(?:connectDevice|confirmDevicePwd|readSportStep|startDetectHeart|startDetectBP|startBloodGlucoseDetect)\s*\(/);
  assert.doesNotMatch(all, /OnCreate\s*\{/);
  assert.match(web, /throw new Error/);
  console.log('[OK] No automatic start, device connection or health-data read implemented');
}

if (require.main === module) {
  try {
    const prior = spawnSync(process.execPath, [path.join(root, 'scripts/check-hband-init.cjs')], {
      cwd: root, encoding: 'utf8', shell: false,
    });
    if (prior.stdout) process.stdout.write(prior.stdout);
    if (prior.stderr) process.stderr.write(prior.stderr);
    if (prior.error) throw prior.error;
    assert.equal(prior.status, 0, 'Dependencies or initializer source checks failed.');
    checkSources();
    console.log('\nSCAN SOURCE CHECK PASSED (STATIC ONLY)');
    console.log('No Android build, HBand execution, RF scan, compatibility or performance test was run.');
  } catch (error) {
    console.error('\n[FAIL] ' + (error instanceof Error ? error.message : String(error)));
    process.exitCode = 1;
  }
}
module.exports = { checkSources };
