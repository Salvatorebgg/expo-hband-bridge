# expo-hband-bridge

Android 原生桥接模块，用于将 Expo / React Native 的 TypeScript 层连接到 HBand / Veepoo Android BLE SDK。

```text
Expo / React Native
        ↓
TypeScript API
        ↓
Expo Modules API
        ↓
Kotlin
        ↓
HBand / Veepoo SDK
        ↓
Android BLE
```

## 当前范围

已实现并在 Android 16 真机完成基础验证：

- 原生模块诊断
- Android 蓝牙权限申请与查询
- 蓝牙开关状态查询
- HBand SDK 初始化
- BLE 扫描启动 / 停止
- 限时扫描
- App 进入后台时停止扫描
- 蓝牙关闭时拒绝扫描、重新开启后恢复
- 扫描状态与事件回传到 TypeScript

当前未实现 / 未验证：

- 真实兼容设备发现结果
- 设备连接 / 断开
- 设备认证
- 心率、血压、睡眠等健康数据读取
- 性能、耗电和长期稳定性测试

> HBand SDK 会过滤 BLE 广播。扫描结果为 0 不等同于扫描流程失败，也不能据此判断某个具体设备是否兼容。

## 环境要求

已验证的开发组合：

| 组件 | 版本 / 要求 |
|---|---|
| Node.js | 24.x |
| npm | 11.x |
| Expo | 57.0.22 |
| React Native | 0.86.3 |
| React | 19.2.3 |
| JDK | 17 |
| Android SDK Platform | 36 |
| Android Build-Tools | 36.0.0 |
| Android Platform-Tools | 需包含 `adb` |
| Android Command-line Tools | latest |

推荐通过 Android Studio 的 SDK Manager 安装 Android SDK 组件。

Windows PowerShell：

```powershell
$env:JAVA_HOME = "<JDK 17 根目录>"
$env:ANDROID_HOME = "<Android SDK 根目录>"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"

node --version
npm.cmd --version
java -version
javac -version
adb version
```

Gradle 使用宿主 Android 工程自己的 Wrapper，不需要全局安装 Gradle。

## Android SDK 与 HBand SDK

### Google Android SDK

负责 Android 工程的编译、构建、安装和调试，包括：

- Android API Platform
- Build-Tools
- Platform-Tools / `adb`
- Command-line Tools

解决的是：

```text
如何开发、编译、安装和调试 Android App
```

### HBand / Veepoo Android BLE SDK

厂商协议 SDK，作为原生依赖保存在 `android/libs/`，负责 HBand / Veepoo 兼容设备的协议与蓝牙能力封装。

解决的是：

```text
Android App 如何调用 HBand / Veepoo 设备能力
```

两者不能互相替代。

当前锁定的主要 AAR：

```text
vpbluetooth-1.20.aar
vpprotocol-2.3.81.15.aar
JL_Watch_V1.13.1_11214-release.aar
jl_rcsp_V0.7.2_527-release.aar
jl_bt_ota_V1.10.0_10931-release.aar
BmpConvert_V1.6.0_10604-release.aar
abpartool-release.aar
```

不要直接替换为 GitHub 最新版；升级厂商 SDK 时单独做兼容性验证。

## 核心目录

```text
expo-hband-bridge/
├── android/
│   ├── libs/                       # HBand / Veepoo AAR
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/expo/modules/hbandbridge/
│   └── build.gradle
├── build/                          # 编译后的 JS / d.ts
├── docs/
├── internal/
├── scripts/
├── src/
│   ├── HBandBridgeModule.ts
│   ├── HBandBridgeModule.web.ts
│   ├── HBandBridge.types.ts
│   └── index.ts
├── tests/
├── expo-module.config.json
├── package.json
├── package-lock.json
├── tsconfig.json
└── tsconfig.hband-build.json
```

## 安装与检查

```powershell
git clone https://github.com/Salvatorebgg/expo-hband-bridge.git
cd expo-hband-bridge

npm.cmd ci
node .\scripts\check-hband-scan.cjs

node .\node_modules\typescript\bin\tsc `
  --project .\tsconfig.hband-build.json `
  --pretty false
```

`tsconfig.hband-build.json` 中需保留：

```json
{
  "sourceMap": false,
  "inlineSources": false
}
```

## 集成到 Expo / React Native Android 项目

当前模块仅支持 Android。

宿主项目需要：

- 支持 Expo Modules
- 使用自定义 Development Build / Android 原生构建
- 不使用 Expo Go 验证该模块

### 本地源码安装

```powershell
npm install <expo-hband-bridge 本地路径>
```

### 打包后安装

在本项目根目录：

```powershell
npm pack
```

在宿主项目中：

```powershell
npm install <生成的 expo-hband-bridge-*.tgz>
```

安装后必须重新构建 Android App，使 Kotlin、Manifest、AAR 和原生依赖进入 APK。

## TypeScript API

```ts
import HBandBridge from 'expo-hband-bridge';
```

主要接口：

```ts
HBandBridge.getBridgeInfo();
HBandBridge.getBluetoothState();

await HBandBridge.getPermissionsAsync();
await HBandBridge.requestPermissionsAsync();

HBandBridge.getSdkState();
await HBandBridge.initializeAsync();

HBandBridge.getScanState();
await HBandBridge.startScanAsync(10);
await HBandBridge.stopScanAsync();
```

事件：

```ts
const deviceSub = HBandBridge.addListener('onDeviceFound', device => {
  console.log(device);
});

const stateSub = HBandBridge.addListener('onScanStateChanged', state => {
  console.log(state);
});

const errorSub = HBandBridge.addListener('onScanError', error => {
  console.error(error);
});

// 页面销毁时
deviceSub.remove();
stateSub.remove();
errorSub.remove();
```

建议调用顺序：

```text
检查蓝牙状态
    ↓
申请权限
    ↓
initializeAsync()
    ↓
startScanAsync()
    ↓
通过事件接收扫描状态 / 设备结果
```

## Android 权限

Android 12+：

```text
BLUETOOTH_SCAN
BLUETOOTH_CONNECT
```

旧版 Android 的扫描分支还涉及位置权限。

权限声明包含在模块 Android Manifest 中；集成到正式宿主后应检查最终 Manifest Merge 结果。

## 修改后的构建要求

### 仅修改 TypeScript

```powershell
node .\node_modules\typescript\bin\tsc `
  --project .\tsconfig.hband-build.json `
  --pretty false
```

然后重新安装 / 更新宿主中的模块包。

### 修改原生内容

以下任一变更都需要重新构建并安装宿主 Android App：

- Kotlin
- AAR
- AndroidManifest
- Gradle 依赖
- `expo-module.config.json`
- 原生接口

Metro / JS Reload 无法替换已经编译进 APK 的原生代码。

## 后续真实设备接入建议

当前桥已完成初始化与扫描基础层。

继续接真实 HBand / Veepoo 设备时，建议按顺序扩展：

```text
扫描
  ↓
连接
  ↓
连接状态回调
  ↓
设备认证
  ↓
个人信息同步
  ↓
健康数据接口
  ↓
业务层标准化数据
```

连接、认证和健康数据逻辑继续封装在原生桥中，不直接写入页面组件。

## 注意

- `android/libs/` 是核心厂商依赖，不是缓存。
- 根目录 `build/` 是 npm 模块入口产物，不要当作 Android 构建缓存删除。
- 不提交 `node_modules/`、`.gradle/`、`.cxx/`、`local.properties`、签名私钥或凭据。
- `peerDependencies: "*"` 不代表所有 Expo / React Native 版本都已经验证。
- 当前已验证的是基础桥与扫描控制，不代表真实设备连接或健康数据读取已经完成。
