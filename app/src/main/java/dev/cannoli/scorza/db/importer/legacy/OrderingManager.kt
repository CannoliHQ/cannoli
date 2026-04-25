package dev.cannoli.scorza.db.importer.legacy

import java.io.File

class OrderingManager(cannoliRoot: File) {
    private val orderingDir = File(cannoliRoot, "Config/Ordering")

    fun loadPlatformOrder(): List<String> = loadOrder("platform_order.txt")
    fun savePlatformOrder(tags: List<String>) = saveOrder("platform_order.txt", tags)

    fun loadCollectionOrder(): List<String> = loadOrder("collection_order.txt")
    fun saveCollectionOrder(names: List<String>) = saveOrder("collection_order.txt", names)

    fun loadToolOrder(): List<String> = loadOrder("tool_order.txt")
    fun saveToolOrder(names: List<String>) = saveOrder("tool_order.txt", names)

    fun loadPortOrder(): List<String> = loadOrder("port_order.txt")
    fun savePortOrder(names: List<String>) = saveOrder("port_order.txt", names)

    private fun loadOrder(filename: String): List<String> {
        val file = File(orderingDir, filename)
        if (!file.exists()) return emptyList()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun saveOrder(filename: String, items: List<String>) {
        orderingDir.mkdirs()
        File(orderingDir, filename).writeText(items.joinToString("\n") + "\n")
    }
}
