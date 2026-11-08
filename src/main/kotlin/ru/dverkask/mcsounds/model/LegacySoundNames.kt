package ru.dverkask.mcsounds.model

import java.util.Properties

object LegacySoundNames {
    private val keys: Map<String, String> by lazy {
        val props = Properties()
        LegacySoundNames::class.java.getResourceAsStream("/data/legacy-bukkit-sounds.properties")?.use { props.load(it) }
        props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    fun keyOf(constant: String): String? = keys[constant]

    fun contains(constant: String): Boolean = constant in keys
}
