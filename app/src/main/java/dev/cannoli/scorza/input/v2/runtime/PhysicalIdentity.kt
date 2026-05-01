package dev.cannoli.scorza.input.v2.runtime

sealed interface PhysicalIdentity {
    data class Bluetooth(val macAddress: String) : PhysicalIdentity
    data class Wired(val vendorId: Int, val productId: Int, val descriptor: String) : PhysicalIdentity
}
