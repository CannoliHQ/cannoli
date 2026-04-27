package dev.cannoli.scorza.input.v2.resolver

import dev.cannoli.scorza.input.v2.ConnectedDevice
import dev.cannoli.scorza.input.v2.DeviceTemplate

interface PaddleboatTemplateImporter {
    fun importFor(device: ConnectedDevice): DeviceTemplate?
}

class NoopPaddleboatTemplateImporter @javax.inject.Inject constructor() : PaddleboatTemplateImporter {
    override fun importFor(device: ConnectedDevice): DeviceTemplate? = null
}
