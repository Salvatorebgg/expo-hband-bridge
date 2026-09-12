package expo.modules.hbandbridge

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class HBandBridgeModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("HBandBridge")

    Function("hello") {
      "Hello world! 👋"
    }
  }
}
