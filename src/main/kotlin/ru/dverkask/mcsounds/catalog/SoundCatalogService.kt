package ru.dverkask.mcsounds.catalog

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.util.io.HttpRequests
import com.intellij.util.messages.Topic
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.model.SoundCatalog
import ru.dverkask.mcsounds.model.SoundsJson
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.model.SoundFile
import ru.dverkask.mcsounds.settings.McSoundsSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*

data class McVersion(val id: String, val type: String, val url: String) {
    val isRelease: Boolean get() = type == "release"
}

sealed interface CatalogStatus {
    data object Idle : CatalogStatus
    data class Loading(val version: String?) : CatalogStatus
    data class Ready(val catalog: SoundCatalog) : CatalogStatus
    data class Failed(val version: String?, val message: String) : CatalogStatus
}

fun interface SoundCatalogListener {
    fun statusChanged(status: CatalogStatus)

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<SoundCatalogListener> = Topic.create("Minecraft sound catalog", SoundCatalogListener::class.java)
    }
}

@Service(Service.Level.APP)
class SoundCatalogService {

    @Volatile var status: CatalogStatus = CatalogStatus.Idle
        private set

    @Volatile var versions: List<McVersion> = emptyList()
        private set

    val catalog: SoundCatalog? get() = (status as? CatalogStatus.Ready)?.catalog

    val cacheDir: Path = Path.of(PathManager.getSystemPath(), "minecraft-sound-browser")

    private val generation = AtomicInteger()

    fun ensureLoaded() {
        when (status) {
            CatalogStatus.Idle -> reload(refreshManifest = false)
            else -> Unit
        }
    }

    fun hasCachedIndex(): Boolean =
        cacheDir.resolve("indexes").let { it.exists() && it.toFile().list()?.isNotEmpty() == true }

    fun reload(refreshManifest: Boolean) {
        val gen = generation.incrementAndGet()
        val requested = McSoundsSettings.getInstance().version.ifBlank { null }
        setStatus(CatalogStatus.Loading(requested), gen)
        val app = ApplicationManager.getApplication()
        app.invokeLater({
            if (gen != generation.get()) return@invokeLater
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                null, McSoundsBundle.message("progress.loading.catalog"), true,
            ) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val catalog = load(requested, refreshManifest, indicator)
                        setStatus(CatalogStatus.Ready(catalog), gen)
                    } catch (e: ProcessCanceledException) {
                        setStatus(CatalogStatus.Failed(requested, McSoundsBundle.message("status.cancelled")), gen)
                    } catch (e: Exception) {
                        LOG.warn("Failed to load Minecraft sounds", e)
                        setStatus(CatalogStatus.Failed(requested, describe(e)), gen)
                    }
                }
            })
        }, ModalityState.any())
    }

    fun selectVersion(id: String) {
        val settings = McSoundsSettings.getInstance()
        if (settings.version == id && status !is CatalogStatus.Failed) return
        settings.version = id
        reload(refreshManifest = false)
    }

    fun currentVersionId(): String? = when (val s = status) {
        is CatalogStatus.Ready -> s.catalog.version
        is CatalogStatus.Loading -> s.version
        is CatalogStatus.Failed -> s.version
        CatalogStatus.Idle -> null
    } ?: McSoundsSettings.getInstance().version.ifBlank { null }

    fun getAudioBytes(file: SoundFile): ByteArray {
        file.localPath?.let { return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(it)) }
        val catalog = catalog ?: throw IOException(McSoundsBundle.message("error.catalog.not.loaded"))
        val hash = catalog.hashOf(file)
            ?: throw IOException(McSoundsBundle.message("error.asset.missing", file.assetPath))
        return getObject(hash)
    }

    fun cacheSizeBytes(): Long =
        if (!cacheDir.exists()) 0 else cacheDir.toFile().walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearCache() {
        if (cacheDir.exists()) cacheDir.toFile().deleteRecursively()
        manifestCache = null
    }

    @Volatile private var manifestCache: JsonObject? = null

    private fun load(requested: String?, refreshManifest: Boolean, indicator: ProgressIndicator): SoundCatalog {
        indicator.isIndeterminate = false
        indicator.text = McSoundsBundle.message("progress.loading.versions")
        indicator.fraction = 0.05
        val manifest = loadManifest(refreshManifest, indicator)
        val all = parseVersions(manifest)
        versions = all

        val latest = manifest.getAsJsonObject("latest")?.get("release")?.asString
        val versionId = requested ?: latest ?: all.first().id
        val version = all.firstOrNull { it.id == versionId }
            ?: throw IOException(McSoundsBundle.message("error.version.unknown", versionId))

        indicator.checkCanceled()
        indicator.text = McSoundsBundle.message("progress.loading.version", version.id)
        indicator.fraction = 0.2
        val versionJson = parse(cachedOrDownload(cacheDir.resolve("versions/${version.id}.json"), version.url, indicator))

        val assetIndex = versionJson.getAsJsonObject("assetIndex")
        val indexId = assetIndex.get("id").asString
        val indexSha = assetIndex.get("sha1")?.asString
        indicator.checkCanceled()
        indicator.text = McSoundsBundle.message("progress.loading.index", indexId)
        indicator.fraction = 0.4
        val indexBytes = cachedOrDownload(
            cacheDir.resolve("indexes/$indexId-${indexSha ?: "latest"}.json"),
            assetIndex.get("url").asString, indicator, expectedSha1 = indexSha,
        )
        val objects = parse(indexBytes).getAsJsonObject("objects")
        val hashes = HashMap<String, String>(objects.size() * 2)
        for ((path, value) in objects.entrySet()) {
            hashes[path] = value.asJsonObject.get("hash").asString
        }

        indicator.checkCanceled()
        indicator.text = McSoundsBundle.message("progress.loading.sounds")
        indicator.fraction = 0.7
        val soundsHash = hashes["minecraft/sounds.json"]
            ?: throw IOException(McSoundsBundle.message("error.no.sounds.json", version.id))
        val events = SoundsJson.parse(parse(getObject(soundsHash, indicator)))
        indicator.fraction = 1.0
        return SoundCatalog(version.id, events, hashes)
    }

    private fun loadManifest(refresh: Boolean, indicator: ProgressIndicator): JsonObject {
        if (!refresh) manifestCache?.let { return it }
        val file = cacheDir.resolve("version_manifest_v2.json")
        val stale = !file.exists() || refresh ||
            System.currentTimeMillis() - file.getLastModifiedTime().toMillis() > MANIFEST_TTL_MS
        val bytes = if (stale) {
            try {
                download(VERSION_MANIFEST, indicator).also { write(file, it) }
            } catch (e: IOException) {
                if (!file.exists()) throw e
                LOG.info("Using cached version manifest: ${e.message}")
                file.readBytes()
            }
        } else file.readBytes()
        return parse(bytes).also { manifestCache = it }
    }

    private fun parseVersions(manifest: JsonObject): List<McVersion> =
        manifest.getAsJsonArray("versions").map { it.asJsonObject }
            .filter { it.get("releaseTime").asString >= FIRST_SOUNDS_JSON_RELEASE }
            .filter { it.get("type").asString == "release" || it.get("type").asString == "snapshot" }
            .map { McVersion(it.get("id").asString, it.get("type").asString, it.get("url").asString) }

    private fun getObject(hash: String, indicator: ProgressIndicator? = null): ByteArray {
        val file = cacheDir.resolve("objects/${hash.take(2)}/$hash")
        return cachedOrDownload(file, "$RESOURCES_BASE/${hash.take(2)}/$hash", indicator, expectedSha1 = hash)
    }

    private fun cachedOrDownload(
        file: Path,
        url: String,
        indicator: ProgressIndicator?,
        expectedSha1: String? = null,
    ): ByteArray {
        if (file.exists()) {
            val bytes = file.readBytes()
            if (expectedSha1 == null || sha1(bytes) == expectedSha1) return bytes
            LOG.info("Cached file is corrupted, downloading again: $file")
        }
        val bytes = download(url, indicator)
        if (expectedSha1 != null && sha1(bytes) != expectedSha1) {
            throw IOException(McSoundsBundle.message("error.checksum", url))
        }
        write(file, bytes)
        return bytes
    }

    private fun download(url: String, indicator: ProgressIndicator?): ByteArray {
        LOG.debug("Downloading $url")
        var attempt = 0
        while (true) {
            try {
                return HttpRequests.request(url)
                    .productNameAsUserAgent()
                    .connectTimeout(10_000)
                    .readTimeout(60_000)
                    .readBytes(indicator)
                    .also { indicator?.isIndeterminate = false }
            } catch (e: IOException) {
                if (e is HttpRequests.HttpStatusException || ++attempt >= DOWNLOAD_ATTEMPTS) throw e
                LOG.info("Download failed (attempt $attempt), retrying: $url (${e.message})")
                indicator?.checkCanceled()
            }
        }
    }

    private fun write(file: Path, bytes: ByteArray) {
        try {
            file.parent.createDirectories()
            val tmp = Files.createTempFile(file.parent, file.name, ".tmp")
            tmp.writeBytes(bytes)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            LOG.warn("Unable to write cache file $file", e)
        }
    }

    private fun setStatus(newStatus: CatalogStatus, gen: Int) {
        if (gen != generation.get()) return
        status = newStatus
        ApplicationManager.getApplication().invokeLater({
            if (gen == generation.get()) {
                ApplicationManager.getApplication().messageBus.syncPublisher(SoundCatalogListener.TOPIC)
                    .statusChanged(newStatus)
            }
        }, ModalityState.any())
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpRequests.HttpStatusException -> McSoundsBundle.message("error.http", e.statusCode, e.url)
        is java.net.UnknownHostException -> McSoundsBundle.message("error.offline")
        is java.net.SocketTimeoutException -> McSoundsBundle.message("error.timeout")
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun parse(bytes: ByteArray): JsonObject = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val LOG = logger<SoundCatalogService>()
        private const val VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        private const val RESOURCES_BASE = "https://resources.download.minecraft.net"
        private const val FIRST_SOUNDS_JSON_RELEASE = "2013-10-25"
        private const val DOWNLOAD_ATTEMPTS = 3
        private const val MANIFEST_TTL_MS = 6 * 60 * 60 * 1000L

        fun getInstance(): SoundCatalogService =
            ApplicationManager.getApplication().getService(SoundCatalogService::class.java)
    }
}
