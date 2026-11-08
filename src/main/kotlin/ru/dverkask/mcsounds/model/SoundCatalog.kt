package ru.dverkask.mcsounds.model

class SoundCatalog(
    val version: String,
    events: List<SoundEvent>,
    private val assetHashes: Map<String, String>,
) {
    val events: List<SoundEvent> = events.sortedBy { it.key }

    private val byKey: Map<String, SoundEvent> = this.events.associateBy { it.key }

    private val byConstant: Map<String, SoundEvent> = this.events.associateBy { it.constantName }

    private val byMojmapConstant: Map<String, SoundEvent> = buildMap {
        val prefixes = listOf("ENTITY_", "BLOCK_", "ITEM_")
        for (event in this@SoundCatalog.events) {
            val c = event.constantName
            val prefix = prefixes.firstOrNull { c.startsWith(it) } ?: continue
            val stripped = c.removePrefix(prefix)
            if (stripped !in byConstant && stripped !in this) put(stripped, event)
        }
    }

    val size: Int get() = events.size

    fun byKey(key: String): SoundEvent? = byKey[key.removePrefix("minecraft:")]

    fun byConstant(name: String, allowMojmap: Boolean = true): SoundEvent? =
        byConstant[name] ?: if (allowMojmap) byMojmapConstant[name] else null

    fun hashOf(file: SoundFile): String? = assetHashes[file.assetPath]
}
