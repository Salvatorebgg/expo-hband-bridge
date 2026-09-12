// Reexport the native module. On web, it will be resolved to HBandBridgeModule.web.ts
// and on native platforms to HBandBridgeModule.ts
export { default } from './HBandBridgeModule';
export * from './HBandBridge.types';
