'use strict';
// Creates a local, private test-host package from already installed, matched dependencies.
// No npm publish, no network request, no Android build, no modification of bridge source.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');
const MARKER = 'hband-step6-local-tarball';
const read = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));
const write = (p, value) => { fs.mkdirSync(path.dirname(p), {recursive:true}); fs.writeFileSync(p, JSON.stringify(value,null,2)+'\n'); };
const digest = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
function needFile(p) { if (!fs.existsSync(p) || !fs.statSync(p).isFile()) throw new Error(`Missing required file: ${p.replace(/\\/g,'/')}`); }
function checkedVersion(v, name, range=false) {
  const pattern = range ? /^[~^]?\d+\.\d+\.\d+$/ : /^\d+\.\d+\.\d+$/;
  if (typeof v !== 'string' || !pattern.test(v)) throw new Error(`Invalid or missing version for ${name}: ${v}`);
  return v;
}
function makePlan(root) {
  root=path.resolve(root); const host=path.join(root,'example');
  const module=read(path.join(root,'package.json'));
  if (module.name!=='expo-hband-bridge') throw new Error('Not the expo-hband-bridge root.');
  checkedVersion(module.version,'module');
  const hp=path.join(host,'package.json');
  if(fs.existsSync(hp) && read(hp).hbandLabManagedBy!==MARKER) throw new Error('Existing example/package.json is unmanaged; refusing to overwrite it.');
  if(!fs.existsSync(hp) && (fs.existsSync(path.join(host,'android')) || fs.existsSync(path.join(host,'ios')))) throw new Error('Unmanaged native host directories exist; refusing to initialize.');
  const versions={};
  for(const name of ['expo','react','react-native','babel-preset-expo','@types/react','typescript']) {
    const p=path.join(root,'node_modules',name,'package.json'); needFile(p);
    versions[name]=checkedVersion(read(p).version,name);
  }
  if(!versions.expo.startsWith('57.')) throw new Error('This step targets the confirmed local Expo 57 setup; stop rather than guessing new versions.');
  const bundled=read(path.join(root,'node_modules/expo/bundledNativeModules.json'));
  for(const name of ['react','react-native']) {
    if(versions[name]!==bundled[name]) throw new Error(`${name} mismatch: installed ${versions[name]}, Expo expects ${bundled[name]}`);
  }
  const devClientRange=checkedVersion(bundled['expo-dev-client'],'expo-dev-client',true);
  for(const name of ['build/index.js','build/index.d.ts','build/HBandBridgeModule.js','build/HBandBridgeModule.d.ts','expo-module.config.json','android/build.gradle','android/hband-sdk.lock.json','android/src/main/AndroidManifest.xml']) needFile(path.join(root,name));
  const config=read(path.join(root,'expo-module.config.json'));
  if(!config.platforms?.includes('android')) throw new Error('Module is not registered for Android.');
  const aars=config.android?.gradleAarProjects;
  if(!Array.isArray(aars)||!aars.length) throw new Error('Missing HBand AAR registrations.');
  for(const a of aars) {
    if(typeof a.aarFilePath!=='string' || !a.aarFilePath.startsWith('android/libs/') || a.aarFilePath.includes('..')) throw new Error('Unsafe AAR path.');
    needFile(path.join(root,a.aarFilePath));
  }
  return {root,host,module,versions,devClientRange};
}
function copyTree(src,dst) {
  const s=fs.lstatSync(src);
  if(s.isSymbolicLink()) throw new Error(`Refusing to copy symlink: ${src}`);
  if(s.isDirectory()) {
    fs.mkdirSync(dst,{recursive:true});
    for(const name of fs.readdirSync(src)) copyTree(path.join(src,name),path.join(dst,name));
  } else if(s.isFile()) { fs.mkdirSync(path.dirname(dst),{recursive:true}); fs.copyFileSync(src,dst); }
  else throw new Error(`Unsupported file kind: ${src}`);
}
function stageModule(plan,stage) {
  fs.mkdirSync(stage,{recursive:true});
  const m=plan.module;
  // No root npm hooks, devDependencies, personal author metadata, test app or local SDK paths.
  write(path.join(stage,'package.json'),{
    name:m.name,version:m.version,private:true,license:m.license||'UNLICENSED',
    description:m.description||'Private HBand Android bridge test snapshot',
    main:'build/index.js',types:'build/index.d.ts',
    files:['build','android','expo-module.config.json','LICENSE'],
    dependencies:m.dependencies||{},
    peerDependencies:m.peerDependencies||{expo:'*',react:'*','react-native':'*'}
  });
  for(const name of ['build','expo-module.config.json','android/build.gradle','android/hband-sdk.lock.json','android/libs','android/src'])
    copyTree(path.join(plan.root,name),path.join(stage,name));
  for(const name of ['LICENSE','android/vendor-notices','android/consumer-rules.pro','android/proguard-rules.pro'])
    if(fs.existsSync(path.join(plan.root,name))) copyTree(path.join(plan.root,name),path.join(stage,name));
}
function prepare(root,{quiet=false}={}) {
  const plan=makePlan(root);
  const temp=fs.mkdtempSync(path.join(os.tmpdir(),'hband-local-pack-'));
  try {
    stageModule(plan,temp);
    // Static command, no paths interpolated into cmd.exe. npm.cmd must already work.
    const win=process.platform==='win32';
    const command=win?(process.env.ComSpec||'cmd.exe'):'npm';
    const args=win?['/d','/s','/c','npm.cmd pack --ignore-scripts --offline --json']:['pack','--ignore-scripts','--offline','--json'];
    const r=spawnSync(command,args,{cwd:temp,encoding:'utf8',windowsHide:true,timeout:120000,maxBuffer:10*1024*1024});
    if(r.error || r.status!==0) throw new Error(`Local npm pack failed.\n${r.error?.message||''}\n${r.stderr||''}\n${r.stdout||''}`);
    let reports;
    try { reports=JSON.parse(r.stdout); } catch { throw new Error('npm pack did not produce JSON.\n'+r.stdout); }
    const file=reports?.[0]?.filename;
    if(typeof file!=='string'||path.basename(file)!==file) throw new Error('Invalid npm pack file name.');
    const packed=path.join(temp,file); needFile(packed);
    const sha256=digest(packed);
    const tarball=`vendor/expo-hband-bridge-${plan.module.version}-${sha256.slice(0,12)}.tgz`;
    fs.mkdirSync(path.join(plan.host,'vendor'),{recursive:true});
    fs.copyFileSync(packed,path.join(plan.host,tarball));
    const v=plan.versions;
    const hostPackage={
      name:'hband-bridge-lab',version:'0.1.0',private:true,license:'UNLICENSED',
      hbandLabManagedBy:MARKER,main:'index.js',
      scripts:{start:'expo start --dev-client',android:'expo run:android --device',typecheck:'tsc --noEmit --pretty false'},
      dependencies:{expo:v.expo,react:v.react,'react-native':v['react-native'],'expo-dev-client':plan.devClientRange,'expo-hband-bridge':'file:./'+tarball},
      devDependencies:{'@types/react':v['@types/react'],'babel-preset-expo':v['babel-preset-expo'],typescript:v.typescript},
      expo:{autolinking:{searchPaths:['./node_modules']}}
    };
    write(path.join(plan.host,'package.json'),hostPackage);
    const record={tarball,sha256,moduleVersion:plan.module.version,versions:v,devClientRange:plan.devClientRange};
    write(path.join(plan.host,'hband-snapshot.json'),record);
    if(!quiet) {
      console.log('[OK] Local bridge tarball created: '+tarball);
      console.log('[OK] Host package.json written; no npm publication');
      for(const [name,version] of Object.entries(hostPackage.dependencies)) console.log(`  ${name} = ${version}`);
      console.log('NEXT: install dependencies inside example/. No Android build or device call has run.');
    }
    return record;
  } finally { fs.rmSync(temp,{recursive:true,force:true}); }
}
if(require.main===module) {
  try { prepare(path.resolve(__dirname,'..')); }
  catch(e) { console.error('[ERROR] '+e.message); process.exitCode=1; }
}
module.exports={makePlan,stageModule,prepare};
