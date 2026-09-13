'use strict';
// Uses the installed host's Expo Autolinking CLI; does not run Gradle or load the SDK.
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const {createRequire}=require('node:module');
const {spawnSync}=require('node:child_process');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const sha=p=>crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function validateResolved(result,aliases) {
  const matches=(result.modules||[]).filter(x=>x.packageName==='expo-hband-bridge');
  if(matches.length!==1) throw new Error('Autolinking must resolve exactly one expo-hband-bridge.');
  const projects=matches[0].projects||[];
  const classes=projects.flatMap(p=>p.modules||[]).map(m=>typeof m==='string'?m:(m.classifier||m.class));
  if(!classes.includes('expo.modules.hbandbridge.HBandBridgeModule')) throw new Error('HBand Kotlin module class is missing from Autolinking results.');
  const actual=projects.flatMap(p=>p.aarProjects||[]).map(a=>a.name).sort();
  const expected=aliases.map(n=>'expo-hband-bridge$'+n).sort();
  if(JSON.stringify(actual)!==JSON.stringify(expected)) throw new Error('Resolved HBand AAR projects do not match the module registrations.');
  return matches[0];
}
function main() {
  const root=path.resolve(__dirname,'..'), host=path.join(root,'example');
  const hp=read(path.join(host,'package.json'));
  if(hp.hbandLabManagedBy!=='hband-step6-local-tarball') throw new Error('Run prepare-hband-example.cjs first.');
  const record=read(path.join(host,'hband-snapshot.json'));
  if(sha(path.join(host,record.tarball))!==record.sha256) throw new Error('Local bridge tarball hash changed.');
  const installed=path.join(host,'node_modules','expo-hband-bridge');
  if(!fs.existsSync(installed)) throw new Error('Run npm install inside example/ first.');
  if(fs.realpathSync(installed)===fs.realpathSync(root)) throw new Error('Expected an installed tarball copy, not the parent module symlink.');
  for(const name of ['expo','react','react-native','babel-preset-expo','@types/react','typescript']) {
    const actual=read(path.join(host,'node_modules',name,'package.json')).version;
    if(actual!==record.versions[name]) throw new Error(`Host version mismatch for ${name}: ${actual}`);
  }
  console.log('[OK] Host Expo / React / React Native and TypeScript versions match the prepared set');
  const config=read(path.join(installed,'expo-module.config.json'));
  const registrations=config.android.gradleAarProjects;
  if(JSON.stringify(config)!==JSON.stringify(read(path.join(root,'expo-module.config.json')))) throw new Error('Bridge registration changed after packaging. Rebuild, prepare and reinstall.');
  for(const a of registrations) {
    if(sha(path.join(installed,a.aarFilePath))!==sha(path.join(root,a.aarFilePath))) throw new Error('Installed AAR differs from source: '+a.aarFilePath);
  }
  console.log(`[OK] Installed module contains ${registrations.length} unchanged HBand AAR files`);
  const req=createRequire(path.join(host,'node_modules','expo','package.json'));
  const autoPkg=req.resolve('expo-modules-autolinking/package.json');
  const auto=read(autoPkg);
  const relative=typeof auto.bin==='string'?auto.bin:auto.bin?.['expo-modules-autolinking'];
  if(!relative) throw new Error('Installed Expo Autolinking CLI entry is missing.');
  const cli=path.resolve(path.dirname(autoPkg),relative);
  function execute(command) {
    const result=spawnSync(process.execPath,[cli,command,'--platform','android','--json'],{
      cwd:host,encoding:'utf8',windowsHide:true,timeout:120000,maxBuffer:20*1024*1024,
      env:{...process.env,FORCE_COLOR:'0'}
    });
    if(result.error || result.status!==0) throw new Error(`Autolinking ${command} failed:\n${result.error?.message||''}\n${result.stderr||''}\n${result.stdout||''}`);
    if(result.stderr.trim()) console.warn(result.stderr.trim());
    try { return JSON.parse(result.stdout); } catch { throw new Error(`Autolinking ${command} returned non-JSON output:\n${result.stdout}`); }
  }
  const found=execute('search');
  if(!found['expo-hband-bridge']) throw new Error('Expo Autolinking did not discover expo-hband-bridge.');
  const duplicates=Object.entries(found).filter(([,v])=>v.duplicates?.length);
  if(duplicates.length) throw new Error('Duplicate native modules detected; do not compile yet: '+duplicates.map(([name])=>name).join(', '));
  const result=execute('resolve');
  const module=validateResolved(result,registrations.map(a=>a.name));
  fs.writeFileSync(path.join(host,'hband-autolinking.json'),JSON.stringify(result,null,2)+'\n');
  console.log('[OK] Expo Autolinking discovered the installed HBand module (no duplicate native modules found by search)');
  console.log('[OK] Kotlin class and '+registrations.length+' AAR projects are present in the Android resolve result');
  console.log('  Source: '+module.projects[0].sourceDir);
  console.log('\nHOST LINK CHECK PASSED');
  console.log('Autolinking configuration only. No Android compilation, SDK initialization, BLE scan or performance test.');
}
if(require.main===module) { try { main(); } catch(e) { console.error('[ERROR] '+e.message); process.exitCode=1; } }
module.exports={validateResolved};
