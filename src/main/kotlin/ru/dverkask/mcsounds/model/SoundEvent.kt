package ru.dverkask.mcsounds.model

data class SoundFile(
    val name: String,
    val volume: Float = 1f,
    val pitch: Float = 1f,
    val weight: Int = 1,
    val stream: Boolean = false,
    val namespace: String = MINECRAFT,
    val localPath: String? = null,
) {
    val assetPath: String get() = "$namespace/sounds/$name.ogg"
    val shortName: String get() = name.substringAfterLast('/')
}

const val MINECRAFT = "minecraft"

data class SoundEvent(
    val key: String,
    val files: List<SoundFile>,
    val subtitle: String? = null,
    val namespace: String = MINECRAFT,
) {
    val isVanilla: Boolean get() = namespace == MINECRAFT && files.none { it.localPath != null }
    val segments: List<String> = key.split('.')
    val category: SoundCategory = SoundCategory.of(segments.first())

    val constantName: String get() = toConstantName(key)

    val namespacedKey: String get() = "$namespace:$key"

    val displayKey: String get() = if (namespace == MINECRAFT) key else namespacedKey

    override fun toString(): String = displayKey

    companion object {
        fun toConstantName(key: String): String = key.uppercase().replace('.', '_')
    }
}

enum class SoundCategory(val id: String, val displayName: String) {
    AMBIENT("ambient", "Ambient"),
    BLOCK("block", "Blocks"),
    ENTITY("entity", "Entities"),
    ITEM("item", "Items"),
    MUSIC("music", "Music"),
    MUSIC_DISC("music_disc", "Music Discs"),
    UI("ui", "Interface"),
    WEATHER("weather", "Weather"),
    ENCHANT("enchant", "Enchantments"),
    EVENT("event", "Events"),
    PARTICLE("particle", "Particles"),
    OTHER("", "Other");

    companion object {
        private val byId = entries.filter { it.id.isNotEmpty() }.associateBy { it.id }
        fun of(id: String): SoundCategory = byId[id] ?: OTHER
    }
}
