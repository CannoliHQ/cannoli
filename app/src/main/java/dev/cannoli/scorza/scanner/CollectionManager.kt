package dev.cannoli.scorza.scanner

import dev.cannoli.scorza.model.Collection
import dev.cannoli.scorza.util.sortedNatural
import java.io.File
import java.io.IOException

class CollectionManager(private val cannoliRoot: File) {
    private val collectionsDir = File(cannoliRoot, "Collections")
    private val orderingDir = File(cannoliRoot, "Config/Ordering")
    private val collectionParentsFile = File(orderingDir, "collection_parents.txt")

    private val favoritesLock = Any()
    @Volatile private var favoritesCache: Set<String>? = null
    @Volatile private var collectionsCache: List<Collection>? = null
    @Volatile private var collectionParentsCache: Map<String, String>? = null
    @Volatile private var childrenByParentCache: Map<String, List<String>>? = null

    // --- Scanning ---

    fun scanCollections(): List<Collection> {
        collectionsCache?.let { return it }
        if (!collectionsDir.exists()) return emptyList()
        val files = collectionsDir.listFiles { f -> f.extension == "txt" } ?: return emptyList()
        val result = files.map { file ->
            Collection(stem = file.nameWithoutExtension, file = file)
        }.sortedNatural { it.displayName }
        collectionsCache = result
        return result
    }

    fun getCollectionStems(): List<String> = scanCollections().map { it.stem }

    fun findLegacyFghStem(): String? = getCollectionStems().firstOrNull {
        val name = Collection.stemToDisplayName(it)
        name.equals("5GH", ignoreCase = true) || name.startsWith("5GH ", ignoreCase = true)
    }

    fun hasCollection(stem: String): Boolean = getCollectionStems().contains(stem)

    fun getGamePaths(stem: String): List<String> {
        val collFile = File(collectionsDir, "$stem.txt")
        if (!collFile.exists()) return emptyList()
        return try {
            collFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: IOException) { emptyList() }
    }

    // --- CRUD ---

    fun addToCollection(stem: String, romPath: String) {
        collectionsDir.mkdirs()
        val collFile = File(collectionsDir, "$stem.txt")
        val existing = try {
            if (collFile.exists()) collFile.readLines().map { it.trim() } else emptyList()
        } catch (_: IOException) { emptyList() }
        if (romPath !in existing) {
            try { collFile.appendText("$romPath\n") } catch (_: IOException) { }
        }
        favoritesCache = null
    }

    fun removeFromCollection(stem: String, romPath: String) {
        val collFile = File(collectionsDir, "$stem.txt")
        if (!collFile.exists()) return
        try {
            val remaining = collFile.readLines().map { it.trim() }.filter { it != romPath && it.isNotEmpty() }
            collFile.writeText(remaining.joinToString("\n") + if (remaining.isNotEmpty()) "\n" else "")
        } catch (_: IOException) { }
        favoritesCache = null
    }

    fun saveCollectionContents(stem: String, romPaths: List<String>) {
        val collFile = File(collectionsDir, "$stem.txt")
        collFile.writeText(romPaths.joinToString("\n") + if (romPaths.isNotEmpty()) "\n" else "")
    }

    fun isInCollection(stem: String, romPath: String): Boolean {
        val collFile = File(collectionsDir, "$stem.txt")
        if (!collFile.exists()) return false
        return try {
            collFile.readLines().any { it.trim() == romPath }
        } catch (_: IOException) { false }
    }

    fun createCollection(displayName: String): String {
        collectionsDir.mkdirs()
        val existingStems = collectionsDir.listFiles { f -> f.extension == "txt" }
            ?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
        val hash = Collection.generateUniqueHash(existingStems, displayName)
        val stem = "${displayName}_$hash"
        File(collectionsDir, "$stem.txt").createNewFile()
        invalidateCollectionsCache()
        return stem
    }

    fun deleteCollection(stem: String) {
        File(collectionsDir, "$stem.txt").delete()
        removeFromCollectionParents(stem)
        invalidateCollectionsCache()
    }

    fun renameCollection(oldStem: String, newDisplayName: String): Boolean {
        val oldFile = File(collectionsDir, "$oldStem.txt")
        if (!oldFile.exists()) return false
        val idx = oldStem.lastIndexOf('_')
        val hash = if (idx >= 0) oldStem.substring(idx + 1) else return false
        val newStem = "${newDisplayName}_$hash"
        val newFile = File(collectionsDir, "$newStem.txt")
        if (newFile.exists()) return false
        val renamed = oldFile.renameTo(newFile)
        if (renamed) {
            renameInCollectionParents(oldStem, newStem)
            invalidateCollectionsCache()
        }
        return renamed
    }

    fun getCollectionsContaining(romPath: String): Set<String> {
        if (!collectionsDir.exists()) return emptySet()
        val result = mutableSetOf<String>()
        collectionsDir.listFiles { f -> f.extension == "txt" }?.forEach { file ->
            try {
                if (file.readLines().any { it.trim() == romPath }) {
                    result.add(file.nameWithoutExtension)
                }
            } catch (_: IOException) { }
        }
        return result
    }

    fun cleanCollectionPaths(deletedPaths: Set<String>) {
        if (!collectionsDir.exists()) return
        collectionsDir.listFiles { f -> f.extension == "txt" }?.forEach { collFile ->
            try {
                val lines = collFile.readLines()
                val cleaned = lines.filter { it.trim() !in deletedPaths }
                if (cleaned.size != lines.size) {
                    collFile.writeText(cleaned.joinToString("\n") + if (cleaned.isNotEmpty()) "\n" else "")
                }
            } catch (_: IOException) { }
        }
        favoritesCache = null
    }

    // --- Favorites ---

    fun getFavoritePaths(): Set<String> {
        favoritesCache?.let { return it }
        return synchronized(favoritesLock) {
            favoritesCache?.let { return it }
            val collFile = File(collectionsDir, "Favorites.txt")
            if (!collFile.exists()) return emptySet()
            val result = try {
                collFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            } catch (_: IOException) { emptySet() }
            favoritesCache = result
            result
        }
    }

    fun invalidateFavorites() {
        favoritesCache = null
    }

    fun invalidateCollectionsCache() {
        collectionsCache = null
    }

    // --- Parent Hierarchy ---

    fun loadCollectionParents(): Map<String, String> {
        collectionParentsCache?.let { return it }
        if (!collectionParentsFile.exists()) return emptyMap()
        return try {
            val pairs = collectionParentsFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && '=' in it }
                .map { line ->
                    val (child, parent) = line.split('=', limit = 2)
                    child.trim() to parent.trim()
                }
                .filter { it.second.isNotEmpty() }
            val map = linkedMapOf<String, String>()
            pairs.forEach { (child, parent) -> map[child] = parent }
            map as Map<String, String>
        } catch (_: IOException) { emptyMap<String, String>() }
            .also {
                collectionParentsCache = it
                childrenByParentCache = buildChildrenIndex(it)
            }
    }

    fun getCollectionParent(childStem: String): String? {
        return loadCollectionParents()[childStem]
    }

    fun setCollectionParent(childStem: String, parentStem: String?) {
        val map = linkedMapOf<String, String>()
        map.putAll(loadCollectionParents())
        if (parentStem == null) map.remove(childStem) else map[childStem] = parentStem
        saveCollectionParents(map)
    }

    fun getChildCollections(parentStem: String): List<String> {
        loadCollectionParents()
        return childrenByParentCache?.get(parentStem) ?: emptyList()
    }

    fun reorderChildren(parentStem: String, orderedChildStems: List<String>) {
        val map = loadCollectionParents()
        val entries = map.entries.toMutableList()
        val firstChildIdx = entries.indexOfFirst { it.value == parentStem }
        entries.removeAll { it.value == parentStem }
        val insertIdx = if (firstChildIdx >= 0) firstChildIdx.coerceAtMost(entries.size) else entries.size
        orderedChildStems.forEachIndexed { i, stem ->
            entries.add(insertIdx + i, java.util.AbstractMap.SimpleEntry(stem, parentStem))
        }
        val newMap = linkedMapOf<String, String>()
        entries.forEach { newMap[it.key] = it.value }
        saveCollectionParents(newMap)
    }

    fun isTopLevelCollection(stem: String): Boolean {
        return getCollectionParent(stem) == null
    }

    fun getDescendants(stem: String): Set<String> {
        loadCollectionParents()
        val index = childrenByParentCache ?: return emptySet()
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(stem)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in index[current] ?: emptyList()) {
                if (result.add(child)) queue.add(child)
            }
        }
        return result
    }

    fun getAncestors(stem: String): Set<String> {
        val allParents = loadCollectionParents()
        val result = mutableSetOf<String>()
        var current = stem
        while (true) {
            val parent = allParents[current] ?: break
            if (!result.add(parent)) break
            current = parent
        }
        return result
    }

    fun setChildCollections(parentStem: String, children: Set<String>) {
        val map = linkedMapOf<String, String>()
        map.putAll(loadCollectionParents())
        val currentChildren = map.entries
            .filter { it.value == parentStem }
            .map { it.key }
            .toSet()
        for (removed in currentChildren - children) {
            map.remove(removed)
        }
        for (added in children - currentChildren) {
            map[added] = parentStem
        }
        saveCollectionParents(map)
    }

    // --- Migration ---

    fun migrateCollectionsToHashedNames() {
        if (!collectionsDir.exists()) return
        val files = collectionsDir.listFiles { f -> f.extension == "txt" } ?: return
        val needsMigration = files.any { file ->
            val stem = file.nameWithoutExtension
            if (stem.equals("Favorites", ignoreCase = true)) return@any false
            val idx = stem.lastIndexOf('_')
            if (idx < 0) return@any true
            val suffix = stem.substring(idx + 1)
            !(suffix.length == 4 && suffix.all { it in '0'..'9' || it in 'a'..'f' })
        }
        if (!needsMigration) return

        val renameMap = mutableMapOf<String, String>()
        val existingStems = files.map { it.nameWithoutExtension }.toMutableSet()

        for (file in files) {
            val oldStem = file.nameWithoutExtension
            if (oldStem.equals("Favorites", ignoreCase = true)) continue
            val idx = oldStem.lastIndexOf('_')
            val alreadyHashed = idx >= 0 && oldStem.substring(idx + 1).let { s ->
                s.length == 4 && s.all { it in '0'..'9' || it in 'a'..'f' }
            }
            if (alreadyHashed) continue

            val hash = Collection.generateUniqueHash(existingStems, oldStem)
            val newStem = "${oldStem}_$hash"
            val newFile = File(collectionsDir, "$newStem.txt")
            if (file.renameTo(newFile)) {
                renameMap[oldStem] = newStem
                existingStems.remove(oldStem)
                existingStems.add(newStem)
            }
        }

        if (renameMap.isEmpty()) return

        if (collectionParentsFile.exists()) {
            val lines = collectionParentsFile.readLines()
            val updated = lines.map { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || '=' !in trimmed) return@map line
                val (child, parent) = trimmed.split('=', limit = 2)
                val newChild = renameMap[child.trim()] ?: child.trim()
                val newParent = renameMap[parent.trim()] ?: parent.trim()
                "$newChild=$newParent"
            }
            collectionParentsFile.writeText(updated.joinToString("\n") + "\n")
        }

        val orderFile = File(orderingDir, "collection_order.txt")
        if (orderFile.exists()) {
            val lines = orderFile.readLines()
            val updated = lines.map { line ->
                val trimmed = line.trim()
                renameMap[trimmed] ?: trimmed
            }
            orderFile.writeText(updated.joinToString("\n") + "\n")
        }

        invalidateCollectionsCache()
        collectionParentsCache = null
        childrenByParentCache = null
    }

    // --- Private ---

    private fun buildChildrenIndex(parents: Map<String, String>): Map<String, List<String>> {
        val index = mutableMapOf<String, MutableList<String>>()
        for ((child, parent) in parents) {
            index.getOrPut(parent) { mutableListOf() }.add(child)
        }
        return index
    }

    private fun saveCollectionParents(map: Map<String, String>) {
        orderingDir.mkdirs()
        val filtered = map.filterValues { it.isNotEmpty() }
        collectionParentsCache = null
        childrenByParentCache = null
        if (filtered.isEmpty()) {
            collectionParentsFile.delete()
            return
        }
        collectionParentsFile.writeText(
            filtered.entries.joinToString("\n") { (child, parent) ->
                "$child=$parent"
            } + "\n"
        )
    }

    private fun removeFromCollectionParents(stem: String) {
        val map = linkedMapOf<String, String>()
        map.putAll(loadCollectionParents())
        map.remove(stem)
        map.entries.removeAll { it.value == stem }
        saveCollectionParents(map)
    }

    private fun renameInCollectionParents(oldStem: String, newStem: String) {
        val map = loadCollectionParents()
        val updated = linkedMapOf<String, String>()
        for ((child, parent) in map) {
            val newChild = if (child == oldStem) newStem else child
            val newParent = if (parent == oldStem) newStem else parent
            updated[newChild] = newParent
        }
        saveCollectionParents(updated)
    }
}
