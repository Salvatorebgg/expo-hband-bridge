'use strict';
// Static, read-only check. No npm installs, Android builds, network calls or BLE calls.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '..');

function fail(message) { throw new Error(message); }
function read(relative) {
  return fs.readFileSync(path.join(root, relative), 'utf8').replace(/^\uFEFF/, '');
}
function readJson(relative) { return JSON.parse(read(relative)); }
function fileInsideRoot(relative) {
  const absolute = path.resolve(root, relative);
  const rel = path.relative(root, absolute);
  if (path.isAbsolute(rel) || rel === '..' || rel.startsWith('..' + path.sep)) {
    fail('Invalid path outside module: ' + relative);
  }
  return absolute;
}

try {
  const config = readJson('expo-module.config.json');
  const lock = readJson('android/hband-sdk.lock.json');
  const gradle = read('android/build.gradle').replace(/\/\/[^\n]*/g, '');
  const manifest = read('android/src/main/AndroidManifest.xml')
    .replace(/<!--[\s\S]*?-->/g, '');
  const expectedModule = 'expo.modules.hbandbridge.HBandBridgeModule';
  if (!config.platforms?.includes('android') || !config.android?.modules?.includes(expectedModule)) {
    fail('Android module registration is missing or has been renamed.');
  }
  const aarProjects = config.android.gradleAarProjects;
  if (lock.schemaVersion !== 1 || !Array.isArray(lock.artifacts) || lock.artifacts.length !== 7) {
    fail('Unexpected dependency lock format or artifact count.');
  }
  if (!Array.isArray(aarProjects) || aarProjects.length !== 7) {
    fail('Expected exactly 7 gradleAarProjects in this step.');
  }
  if (new Set(aarProjects.map(a => a.name)).size !== aarProjects.length) {
    fail('Duplicate AAR alias in gradleAarProjects.');
  }
  const names = new Set();
  for (const entry of lock.artifacts) {
    const location = fileInsideRoot(entry.path);
    if (!fs.existsSync(location)) fail('Missing AAR: ' + entry.path);
    const bytes = fs.readFileSync(location);
    const actualHash = crypto.createHash('sha256').update(bytes).digest('hex');
    if (actualHash !== entry.sha256) fail('Hash mismatch: ' + entry.path);
    if (bytes.length !== entry.bytes) fail('Size mismatch: ' + entry.path);
    if (!aarProjects.some(a => a.name === entry.alias && a.aarFilePath === entry.path)) {
      fail('AAR registration does not match the lock: ' + entry.alias);
    }
    const declaration = "implementation project(path: ':' + project.name + '$" + entry.alias + "')";
    if (!gradle.includes(declaration)) fail('Missing Gradle AAR declaration: ' + entry.alias);
    names.add(path.basename(location));
    console.log('[OK] ' + path.basename(location) + ' / SHA-256 matches');
  }
  for (const name of fs.readdirSync(path.join(root, 'android/libs'))) {
    if (/\.(aar|jar)$/i.test(name) && !names.has(name)) {
      fail('Unexpected binary in android/libs: ' + name + '. Do not delete it blindly; check its origin.');
    }
  }
  if (!Array.isArray(lock.externalDependencies) || lock.externalDependencies.length !== 5) {
    fail('Expected 5 external Maven declarations.');
  }
  for (const coordinate of lock.externalDependencies) {
    if (!gradle.includes("implementation '" + coordinate + "'")) {
      fail('Missing Maven declaration: ' + coordinate);
    }
  }
  const serviceTags = manifest.match(/<service\b[\s\S]*?\/>/g) || [];
  const service = serviceTags.find(s => s.includes('android:name="com.inuker.bluetooth.library.BluetoothService"'));
  if (!service || !service.includes('android:exported="false"')) {
    fail('SDK BluetoothService declaration is missing or exported unexpectedly.');
  }
  console.log('[OK] 7 AAR registrations and Gradle declarations match');
  console.log('[OK] 5 external Maven dependencies declared (NOT downloaded or resolved)');
  console.log('[OK] BluetoothService declared as non-exported');
  console.log('\nSTATIC CHECK PASSED');
  console.log('No Android build, native SDK initialization, BLE scan or device connection was tested.');
} catch (error) {
  console.error('\n[FAIL] ' + (error instanceof Error ? error.message : String(error)));
  process.exitCode = 1;
}
