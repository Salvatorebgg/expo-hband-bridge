import { registerWebModule, NativeModule } from 'expo';

// HBandBridgeModule is not available on the web platform.
class HBandBridgeModule extends NativeModule<{}> {}

export default registerWebModule(HBandBridgeModule, 'HBandBridgeModule');
