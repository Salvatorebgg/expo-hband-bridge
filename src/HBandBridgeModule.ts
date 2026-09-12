import { NativeModule, requireNativeModule } from 'expo';

declare class HBandBridgeModule extends NativeModule<{}> {
  hello(): string;
}

export default requireNativeModule<HBandBridgeModule>('HBandBridge');
