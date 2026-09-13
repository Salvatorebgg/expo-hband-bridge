'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const script = path.resolve(__dirname, '../scripts/prepare-hband-example.cjs');

test('preparation script exists', () => assert.ok(fs.existsSync(script), 'Missing host preparation implementation'));

function api() { return require(script); }
function write(root, name, value) {
  const p = path.join(root, name); fs.mkdirSync(path.dirname(p), {recursive:true});
  fs.writeFileSync(p, typeof value === 'string' ? value : JSON.stringify(value));
}
function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'hband-host-test-'));
  t.after(() => fs.rmSync(root, {recursive:true, force:true}));
  write(root,'package.json',{name:'expo-hband-bridge',version:'0.1.0',license:'UNLICENSED',scripts:{prepare:'NEVER_RUN'},dependencies:{},peerDependencies:{expo:'*',react:'*','react-native':'*'}});
  for(const [name, version] of Object.entries({expo:'57.0.22',react:'19.2.3','react-native':'0.86.3','babel-preset-expo':'57.0.11','@types/react':'19.2.18',typescript:'5.9.2'}))
    write(root,`node_modules/${name}/package.json`,{name,version});
  write(root,'node_modules/expo/bundledNativeModules.json',{react:'19.2.3','react-native':'0.86.3','expo-dev-client':'~57.0.19'});
  write(root,'build/index.js','export { default } from "./HBandBridgeModule";');
  write(root,'build/index.d.ts','export { default } from "./HBandBridgeModule";');
  write(root,'build/HBandBridgeModule.js','export default {};');
  write(root,'build/HBandBridgeModule.d.ts','declare const x: {}; export default x;');
  write(root,'android/build.gradle','plugins { id "com.android.library" }');
  write(root,'android/src/main/AndroidManifest.xml','<manifest />');
  write(root,'android/src/main/java/HBandBridgeModule.kt','class HBandBridgeModule');
  write(root,'android/hband-sdk.lock.json',{});
  write(root,'android/vendor-notices/NOTICE','VENDOR NOTICE');
  write(root,'LICENSE','PRIVATE MODULE NOTICE');
  write(root,'expo-module.config.json',{platforms:['android'],android:{modules:['expo.modules.hbandbridge.HBandBridgeModule'],gradleAarProjects:[{name:'fake',aarFilePath:'android/libs/fake.aar'}]}});
  write(root,'android/libs/fake.aar','FAKE BINARY FOR PACKAGING TEST ONLY');
  return root;
}

test('version plan uses installed versions and bundled dev-client, never latest', t => {
  const p=api().makePlan(fixture(t));
  assert.equal(p.versions.expo,'57.0.22'); assert.equal(p.devClientRange,'~57.0.19');
  assert.equal(p.versions['react-native'],'0.86.3');
});
test('mismatched React is rejected before writes', t => {
  const r=fixture(t); write(r,'node_modules/react/package.json',{version:'19.3.0'});
  assert.throws(()=>api().makePlan(r),/react.*mismatch/i); assert.ok(!fs.existsSync(path.join(r,'example')));
});
test('missing dev-client pin is rejected', t => {
  const r=fixture(t); write(r,'node_modules/expo/bundledNativeModules.json',{react:'19.2.3','react-native':'0.86.3'});
  assert.throws(()=>api().makePlan(r),/expo-dev-client/i);
});
test('a missing compiled entry prevents packaging', t => {
  const r=fixture(t); fs.unlinkSync(path.join(r,'build/index.d.ts'));
  assert.throws(()=>api().makePlan(r),/build\/index.d.ts/);
});
test('an unrelated existing example project is never overwritten', t => {
  const r=fixture(t); write(r,'example/package.json',{name:'important-project'});
  assert.throws(()=>api().makePlan(r),/unmanaged/i);
});
test('runtime package excludes dev dependencies, hooks, and private source extras', t => {
  const r=fixture(t); write(r,'.env','SECRET'); write(r,'android/local.properties','sdk.dir=PRIVATE');
  write(r,'android/build/generated/trash','NO'); write(r,'example/secret','NO');
  const stage=path.join(r,'stage'); const plan=api().makePlan(r); api().stageModule(plan,stage);
  const m=JSON.parse(fs.readFileSync(path.join(stage,'package.json'),'utf8'));
  assert.equal(m.scripts,undefined); assert.equal(m.devDependencies,undefined); assert.equal(m.private,true);
  assert.ok(!fs.existsSync(path.join(stage,'android/local.properties')));
  assert.ok(!fs.existsSync(path.join(stage,'android/build'))); assert.ok(!fs.existsSync(path.join(stage,'example')));
  assert.equal(fs.readFileSync(path.join(stage,'android/libs/fake.aar'),'utf8'),'FAKE BINARY FOR PACKAGING TEST ONLY');
  assert.equal(fs.readFileSync(path.join(stage,'android/vendor-notices/NOTICE'),'utf8'),'VENDOR NOTICE');
});
test('local npm pack produces a real tarball and does not mutate root package', t => {
  const r=fixture(t); const before=fs.readFileSync(path.join(r,'package.json'),'utf8');
  const result=api().prepare(r,{quiet:true});
  assert.ok(fs.statSync(path.join(r,'example',result.tarball)).size>0);
  assert.match(result.tarball,/vendor\/expo-hband-bridge-0\.1\.0-[0-9a-f]{12}\.tgz$/);
  const host=JSON.parse(fs.readFileSync(path.join(r,'example/package.json'),'utf8'));
  assert.equal(host.dependencies['expo-hband-bridge'],'file:./'+result.tarball);
  assert.equal(host.dependencies.expo,'57.0.22'); assert.equal(host.dependencies['expo-dev-client'],'~57.0.19');
  assert.equal(fs.readFileSync(path.join(r,'package.json'),'utf8'),before);
  const result2=api().prepare(r,{quiet:true}); assert.equal(result.tarball,result2.tarball);
  fs.appendFileSync(path.join(r,'build/index.js'),'\n// changed');
  const result3=api().prepare(r,{quiet:true}); assert.notEqual(result.tarball,result3.tarball);
});

function validator() { return require('../scripts/check-hband-example.cjs').validateResolved; }
function resolution(names=['fake'], classifier='expo.modules.hbandbridge.HBandBridgeModule') {
  return {modules:[{packageName:'expo-hband-bridge',projects:[{name:'expo-hband-bridge',modules:[{classifier}],aarProjects:names.map(n=>({name:`expo-hband-bridge$${n}`}))}]}]};
}
test('autolinking validator accepts the bridge class and the exact AAR names', () => assert.doesNotThrow(()=>validator()(resolution(),['fake'])));
test('autolinking validator rejects a missing bridge', () => assert.throws(()=>validator()({modules:[]},['fake']),/bridge/i));
test('autolinking validator rejects a wrong Kotlin class', () => assert.throws(()=>validator()(resolution(['fake'],'WrongClass'),['fake']),/class/i));
test('autolinking validator rejects missing or duplicate AAR entries', () => {
  assert.throws(()=>validator()(resolution([]),['fake']),/AAR/);
  assert.throws(()=>validator()(resolution(['fake','fake']),['fake']),/AAR/);
});
