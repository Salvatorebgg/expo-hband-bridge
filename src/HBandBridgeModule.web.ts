import { NativeModule, registerWebModule } from 'expo';

import type {
  HBandBluetoothInfo,
  HBandBridgeEvents,
  HBandScanInfo,
  HBandBridgeInfo,
  HBandPermissionResult,
  HBandSdkInfo,
} from './HBandBridge.types';

function unsupported(): never {
  throw new Error('HBandBridge 仅支持 Android 原生环境，不能在网页中调用。');
}

class HBandBridgeModule extends NativeModule<HBandBridgeEvents> {
  getScanState(): HBandScanInfo { return unsupported(); }

  async startScanAsync(_durationSeconds: number): Promise<HBandScanInfo> { return unsupported(); }

  async stopScanAsync(): Promise<HBandScanInfo> { return unsupported(); }

  hello(): string {
    return unsupported();
  }

  getBridgeInfo(): HBandBridgeInfo {
    return unsupported();
  }

  getSdkState(): HBandSdkInfo {
    return unsupported();
  }

  async initializeAsync(): Promise<HBandSdkInfo> {
    return unsupported();
  }

  getBluetoothState(): HBandBluetoothInfo {
    return unsupported();
  }

  async getPermissionsAsync(): Promise<HBandPermissionResult> {
    return unsupported();
  }

  async requestPermissionsAsync(): Promise<HBandPermissionResult> {
    return unsupported();
  }
}

export default registerWebModule(HBandBridgeModule, 'HBandBridge');
