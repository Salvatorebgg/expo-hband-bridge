/** 桥接模块的诊断信息；保留上一轮接口。 */
export type HBandBridgeInfo = {
  moduleName: 'HBandBridge';
  bridgeVersion: string;
  platform: 'android';
  manufacturer: string;
  model: string;
  androidVersion: string;
  androidApiLevel: number;
  /**
   * 兼容旧字段：仅表示本进程中桥观察到初始化调用正常返回。
   * 不是“设备已连接”或“SDK 已完成全部运行验证”。
   * @deprecated 新调用方请使用 getSdkState().initCallCompleted。
   */
  hbandSdkIntegrated: boolean;
};

export type HBandBluetoothState =
  | 'unsupported'
  | 'unknown'
  | 'poweredOn'
  | 'poweredOff'
  | 'turningOn'
  | 'turningOff';

/** 手机蓝牙状态快照，不代表 HBand 设备已经连接。 */
export type HBandBluetoothInfo = {
  bleSupported: boolean;
  state: HBandBluetoothState;
  /** 本模块所需、但当前未获得的运行时权限。 */
  missingPermissions: string[];
};

/** Expo 权限管理器的汇总结果。 */
export type HBandPermissionResult = {
  status: 'granted' | 'denied' | 'undetermined';
  /** 本次查询/请求的权限是否全部获得。 */
  granted: boolean;
  canAskAgain: boolean;
  expires: 'never' | number;
};

/** 桥对 SDK 初始化调用的观察状态，不是 BLE 连接状态。 */
export type HBandSdkInitializationState =
  | 'notInitialized'
  | 'initializing'
  | 'initCalled'
  | 'failed';

export type HBandSdkInfo = {
  state: HBandSdkInitializationState;

  /** 仅表示 init 及本桥配置调用已正常返回，不代表异步服务已就绪。 */
  initCallCompleted: boolean;

  /** 本轮锁定的 AAR 版本，不是运行时测得的加载/固件版本。 */
  configuredProtocolVersion: string;

  /** 厂商调用抛错后，可能留下部分静态状态；修复后需重启 App 进程。 */
  requiresProcessRestart: boolean;

  lastErrorCode: string | null;
  lastErrorMessage: string | null;
};


/** 这是桥观察到的扫描状态，不是连接状态。 */
export type HBandScanState = 'idle' | 'starting' | 'scanning' | 'stopping' | 'stopped' | 'failed';
export type HBandScanStopReason =
  | 'requested' | 'timeout' | 'background' | 'moduleDestroyed'
  | 'bluetoothOff' | 'permissionRevoked' | 'locationDisabled'
  | 'sdkStopped' | 'sdkCanceled' | 'error';

export type HBandScanInfo = {
  scanId: string | null;
  state: HBandScanState;
  durationSeconds: number | null;
  /** 是否观察到 SDK 的 onSearchStarted，不代表连接成功。 */
  sdkStartObserved: boolean;
  deviceCount: number;
  resultLimitReached: boolean;
  stopReason: HBandScanStopReason | null;
  /** 仅表示收到 SDK 的 onSearchStopped / onSearchCanceled。 */
  stopConfirmed: boolean;
  requiresProcessRestart: boolean;
  lastErrorCode: string | null;
  lastErrorMessage: string | null;
};

export type HBandScanDevice = {
  scanId: string;
  /** 仅作为本次扫描的临时设备标识，不直接用作患者身份或永久绑定键。 */
  address: string;
  name: string | null;
  rssi: number;
  /** 本桥收到回调时的 Unix 毫秒时间，不是健康测量时间。 */
  observedAt: number;
  /** 此来源已受厂商过滤；不保证兼容，更不代表设备已被认证。 */
  source: 'hbandSdkScan';
};

export type HBandScanError = {
  scanId: string | null;
  code: string;
  message: string;
  requiresProcessRestart: boolean;
};

export type HBandBridgeEvents = {
  onScanStateChanged(event: HBandScanInfo): void;
  onDeviceFound(event: HBandScanDevice): void;
  onScanError(event: HBandScanError): void;
};
