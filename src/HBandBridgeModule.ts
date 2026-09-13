import { NativeModule, requireNativeModule } from 'expo';

import type {
  HBandBluetoothInfo,
  HBandBridgeEvents,
  HBandScanInfo,
  HBandBridgeInfo,
  HBandPermissionResult,
  HBandSdkInfo,
} from './HBandBridge.types';

declare class HBandBridgeModule extends NativeModule<HBandBridgeEvents> {
  getScanState(): HBandScanInfo;
  /** 只提交扫描请求。先订阅事件，再调用；有效时长为整数 2–30 秒。 */
  startScanAsync(durationSeconds: number): Promise<HBandScanInfo>;
  /** 返回 stopping 不代表已停，请观察后续状态事件。 */
  stopScanAsync(): Promise<HBandScanInfo>;

  hello(): string;
  getBridgeInfo(): HBandBridgeInfo;

  getSdkState(): HBandSdkInfo;

  initializeAsync(): Promise<HBandSdkInfo>;
  getBluetoothState(): HBandBluetoothInfo;
  getPermissionsAsync(): Promise<HBandPermissionResult>;
  requestPermissionsAsync(): Promise<HBandPermissionResult>;
}

export default requireNativeModule<HBandBridgeModule>('HBandBridge');
