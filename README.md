# expo-hband-bridge

Android 平台的 Expo 原生模块：`TypeScript → Expo Modules API → Kotlin → HBand/Veepoo SDK → Android BLE`。`example/` 是最小测试宿主，不包含患者业务或后端接口。

**当前范围：** 原生诊断、权限管理、蓝牙状态、SDK 初始化、限时扫描、手动/后台停止和蓝牙关闭后的恢复。已在 Android 16 真机完成基础验证；尚未验证真实设备发现，未实现连接、认证或健康数据读取，不含性能验收。

## 1. 环境

以下命令使用 **Windows PowerShell**，均基于接手人的实际安装路径。macOS/Linux 需替换环境变量语法及 `.cmd`、`.bat` 入口；当前交付只含 Android 原生实现。

| 下载/安装 | 要求 |
|---|---|
| [Node.js](https://nodejs.org/en/download) | Node 24 LTS + npm 11；原验证版本为 `24.19.0 / 11.17.0` |
| [JDK](https://learn.microsoft.com/en-us/java/openjdk/download#openjdk-17) | JDK 17，不是仅安装 JRE |
| [Android Studio](https://developer.android.com/studio) | 使用 SDK Manager 安装下面的组件；已有命令行工具链可不安装 IDE |
| Google Android SDK | Platform **36**、Build-Tools **36.0.0**、Platform-Tools、Command-line Tools；完成所需许可确认 |
| NDK / CMake | 按生成的 `example/android` 工程指定版本安装，不随意升级 |
| 安卓真机 | BLE、USB 调试已开启，并授权当前电脑；系统版本和 ABI 须满足 APK 要求 |

Gradle 使用工程的 Wrapper，不需要全局安装。Expo CLI 随项目依赖安装；**不能用 Expo Go 测试此原生模块**。

基线：**Expo `57.0.22` / React Native `0.86.3` / React `19.2.3`**。其余版本按两级 `package-lock.json` 恢复，不使用 `latest` 重建模板。

**两种 SDK 的区别：** Google Android SDK 是电脑端的构建、安装和调试工具；[HBand Android_Ble_SDK](https://github.com/HBandSDK/Android_Ble_SDK) 是编译进 App 的厂商协议库。后者已放在 `android/libs/`，核心版本为 `vpprotocol-2.3.81.15` 和 `vpbluetooth-1.20`；保留全部 7 个 AAR、版本锁定和注册配置，无需重新下载最新版。

按本机路径设置环境变量，或写入系统环境变量后重开终端：

```powershell
$env:JAVA_HOME = "<本机 JDK 17 根目录>"
$env:ANDROID_HOME = "<本机 Google Android SDK 根目录>"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"

node --version
npm.cmd --version
java -version
javac -version
adb version
```

`JAVA_HOME` 不带 `bin`；`ANDROID_HOME` 不是项目的 `android/`。不要沿用交接方 `local.properties` 中的绝对 `sdk.dir`。首次构建需要能访问 npm、Google Maven、Maven Central 和 Gradle 分发源。

## 2. 新环境恢复与编译

**复现下面的测试流程，需要保留轻量 `example/` 源码。** 可以排除其 `node_modules`、构建输出和缓存；完全不交 `example/` 时，应将模块安装到接手人的 Expo 宿主，见第 5 节。

在 **`expo-hband-bridge` 根目录**执行。各命令按顺序运行，任一步失败即停止。

```powershell
npm.cmd ci
node .\scripts\check-hband-scan.cjs
node .\node_modules\typescript\bin\tsc --project .\tsconfig.hband-build.json --pretty false

Set-Location .\example
npm.cmd ci
node .\node_modules\typescript\bin\tsc --noEmit --pretty false
node ..\scripts\check-hband-example.cjs
```

`example/package.json` 引用本地 `vendor/*.tgz`。上述 `npm ci` 要求其锁文件、所引用的 `.tgz` 和 `hband-snapshot.json` 随交接包存在且一致。文件缺失或要使用最新桥源码时，先执行第 5 节的“更新桥模块副本”，再继续构建；准备脚本不会恢复被删除的 `App.tsx` 等页面文件。

在 **`example/` 目录**生成测试 App 的原生工程并构建：

```powershell
node .\node_modules\expo\bin\cli prebuild --platform android --no-install --skip-dependency-update "react,react-native"

Set-Location .\android
.\gradlew.bat :app:assembleDebug --console=plain --stacktrace
Set-Location ..
```

若交付了原生工程且没有 App 配置变更，可直接使用其 Wrapper 构建；重新 Prebuild 前保留手工改动。**禁止在桥根目录执行 Prebuild，根目录的 `android/` 是核心模块源码。**

成功标志：`BUILD SUCCESSFUL`，产物相对于模块根目录位于：

```text
example/android/app/build/outputs/apk/debug/app-debug.apk
```

## 3. 安装到新手机并启动调试

在 **`example/` 目录**执行；从 `adb devices -l` 选择本次手机，所有 adb 命令使用同一序列号：

```powershell
adb devices -l
$serial = Read-Host "输入目标手机序列号"
adb -s $serial get-state
# 必须返回 device；unauthorized 时先在手机确认 USB 调试授权。

adb -s $serial install -r .\android\app\build\outputs\apk\debug\app-debug.apk
# 安装应返回 Success。
adb -s $serial reverse tcp:8081 tcp:8081

node --dns-result-order=ipv4first .\node_modules\expo\bin\cli start --dev-client --localhost --port 8081
```

保持 Metro 窗口运行和 USB 连接。在手机打开 **HBand Bridge Lab**，在开发启动器连接 `http://127.0.0.1:8081`，或在 Metro 窗口按 `a` 选择目标设备。不要切换 Expo Go。

**仅换终端或重启电脑：** 配好新会话的环境变量后，进入 `example/`，重新设置目标手机的 `adb reverse` 并启动上述 Metro 即可，不用再次安装依赖或构建。**仅换手机：** 兼容时复用 APK，重新安装、USB 授权、转发和 App 蓝牙授权。

## 4. 手机验证

按顺序操作：**检查桥与状态 → 申请蓝牙权限 → 初始化 HBand SDK → 扫描 10 秒 → 再检查桥与状态**。已有权限可跳过授权，SDK 状态已是 `initCalled` 时无需重复初始化。

| 检查点 | 期望结果 |
|---|---|
| 原生调用 | 返回 `HBandBridge` 和当前手机真实信息 |
| 蓝牙与权限 | `poweredOn`、`missingPermissions: []`、`granted: true` |
| 初始化调用 | `sdk.state: initCalled`、`initCallCompleted: true` |
| 扫描结束 | `scan.state: stopped`、`sdkStartObserved: true`、`stopConfirmed: true`、无错误 |
| 补充回归 | 手动停止为 `requested`；切后台停止为 `background`；蓝牙关闭拒绝扫描，开启后可恢复 |

`startScanAsync()` 返回状态，不返回设备数组；设备通过 `onDeviceFound` 事件回传。HBand SDK 会过滤广播，零结果不能证明发现功能通过，也不直接等于扫描控制失败。`initCalled` 仅表示初始化调用返回，不代表设备连接成功。

## 5. 修改与集成

测试 App 安装的是 **`.tgz` 快照，不是父目录源码软链接**。改桥源码后，先停止 Metro，在**模块根目录**更新测试副本：

```powershell
node .\node_modules\typescript\bin\tsc --project .\tsconfig.hband-build.json --pretty false
node .\scripts\prepare-hband-example.cjs
Set-Location .\example
npm.cmd install
node ..\scripts\check-hband-example.cjs
```

准备脚本会生成新 `.tgz` 并改写受其管理的 `example/package.json`，因此此处使用 `npm install` 更新锁文件，提交更新后的锁文件和快照记录。不要直接修改 `example/node_modules/expo-hband-bridge`。

| 改动 | 后续操作 |
|---|---|
| 仅 `example/App.tsx` | Metro 刷新，不重建 APK |
| 桥的纯 TypeScript 逻辑，原生接口未变 | 更新模块副本，重启 Metro |
| Kotlin、AAR、Manifest、原生依赖或新增原生接口 | 更新副本，按第 2 节同步/构建原生工程，重新安装 APK |

**接入其他 App：** 在兼容的 Expo Android 宿主中执行 `npm install <交付的桥接模块.tgz>`，重新构建包含本模块的开发版。模块默认导出 `HBandBridge`；完整接口和事件类型见 `src/HBandBridgeModule.ts`、`src/HBandBridge.types.ts`。`peerDependencies: "*"` 不代表所有 Expo/RN 版本都已经验证。

## 6. 交接与排障

**保留：** `src/`、根目录 `build/`、`android/src/`、`android/libs/`、厂商声明、构建/注册配置、根目录 package/lock、`tsconfig*`、`internal/`、`scripts/`。测试复现另保留 `example` 页面与配置、package/lock、`vendor` 当前引用包和 `hband-snapshot.json`。

**排除：** 各层 `node_modules/`、`.gradle/`、`.cxx/`、`.expo/`、Android 构建输出、`local.properties`、凭据文件及签名私钥。不要把根目录 `build/` 当作 Android 构建缓存；模块入口依赖其中的 JS 和类型声明。

| 问题 | 处理 |
|---|---|
| 手机连不上 `127.0.0.1:8081` | 检查当前设备的 reverse；Metro 必须继续运行并使用上面的 `ipv4first` 启动参数 |
| 原生模块不存在/修改不生效 | 确认不是 Expo Go；更新 `.tgz` 副本并安装匹配的新 APK |
| TS5051 | 在 `tsconfig.hband-build.json` 保留 `sourceMap: false` 与 `inlineSources: false` |
| 换电脑后安装签名冲突 | 按团队签名方案处理；确认可丢弃测试数据后才卸载旧 App，不自动卸载 |
| `requiresProcessRestart: true` | 保留错误并修复原因，重启 App 进程；仅 Reload JS 不足以重置原生单例 |
| 界面 JSON 停在 starting/stopping | “最近一次调用”是旧快照；对照事件并重新点击“检查桥与状态” |

在另一个已配置环境的终端诊断：

```powershell
$serial = Read-Host "输入目标手机序列号"
adb -s $serial reverse --list
curl.exe --noproxy "*" --max-time 10 http://127.0.0.1:8081/status
# 期望 packager-status:running；必要时在手机浏览器访问同一 /status。
adb -s $serial logcat -b crash -d -v time
```

参考：[独立 Expo 模块](https://docs.expo.dev/modules/use-standalone-expo-module-in-your-project/) · [Prebuild](https://docs.expo.dev/workflow/prebuild/) · [USB 真机调试](https://reactnative.dev/docs/running-on-device) · [npm ci](https://docs.npmjs.com/cli/v11/commands/npm-ci/) · [Node IPv4 优先](https://nodejs.org/api/cli.html#--dns-result-orderorder)
