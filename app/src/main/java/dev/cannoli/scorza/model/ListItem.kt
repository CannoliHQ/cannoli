package dev.cannoli.scorza.model

sealed interface ListItem {
    data class RomItem(val rom: Rom) : ListItem
    data class AppItem(val app: App) : ListItem
    data class SubfolderItem(val name: String, val path: String) : ListItem
    data class CollectionItem(val collection: Collection) : ListItem
    data class ChildCollectionItem(val collection: Collection) : ListItem
}
