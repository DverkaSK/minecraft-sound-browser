package ru.dverkask.mcsounds.model

import com.google.gson.JsonObject

object SoundsJson {

    fun parse(
        json: JsonObject,
        namespace: String = MINECRAFT,
        locate: (String, String) -> String? = { _, _ -> null },
    ): List<SoundEvent> {
        val raw = LinkedHashMap<String, JsonObject>()
        for ((key, value) in json.entrySet()) if (value.isJsonObject) raw[key] = value.asJsonObject

        val resolved = HashMap<String, List<SoundFile>>()
        fun resolve(key: String, depth: Int): List<SoundFile> {
            resolved[key]?.let { return it }
            if (depth > 8) return emptyList()
            val sounds = raw[key]?.get("sounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            val files = sounds.flatMap { element ->
                if (element.isJsonPrimitive) {
                    listOfNotNull(file(element.asString, 1f, 1f, 1, false, locate))
                } else if (element.isJsonObject) {
                    val o = element.asJsonObject
                    val name = o.get("name")?.asString.orEmpty()
                    val volume = o.get("volume")?.asFloat ?: 1f
                    val pitch = o.get("pitch")?.asFloat ?: 1f
                    if (o.get("type")?.asString == "event") {
                        val (ns, path) = split(name)
                        if (ns != namespace) emptyList()
                        else resolve(path, depth + 1).map { it.copy(volume = it.volume * volume, pitch = it.pitch * pitch) }
                    } else if (name.isNotBlank()) {
                        listOfNotNull(
                            file(name, volume, pitch, o.get("weight")?.asInt ?: 1, o.get("stream")?.asBoolean ?: false, locate)
                        )
                    } else emptyList()
                } else emptyList()
            }
            resolved[key] = files
            return files
        }

        return raw.keys.mapNotNull { key ->
            val files = resolve(key, 0)
            if (files.isEmpty()) null
            else SoundEvent(key, files, raw[key]?.get("subtitle")?.takeIf { it.isJsonPrimitive }?.asString, namespace)
        }
    }

    fun split(id: String): Pair<String, String> {
        val colon = id.indexOf(':')
        return if (colon < 0) MINECRAFT to id else id.substring(0, colon) to id.substring(colon + 1)
    }

    private fun file(
        id: String, volume: Float, pitch: Float, weight: Int, stream: Boolean, locate: (String, String) -> String?,
    ): SoundFile? {
        val (ns, path) = split(id)
        val local = locate(ns, path)
        if (local == null && ns != MINECRAFT) return null
        return SoundFile(path, volume, pitch, weight, stream, ns, local)
    }
}
