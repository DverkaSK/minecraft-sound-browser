package ru.dverkask.mcsounds.audio

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import ru.dverkask.mcsounds.McSoundsBundle
import ru.dverkask.mcsounds.catalog.SoundCatalogService
import ru.dverkask.mcsounds.model.SoundEvent
import ru.dverkask.mcsounds.model.SoundFile
import ru.dverkask.mcsounds.settings.McSoundsSettings
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

enum class PlayerState { EMPTY, LOADING, READY, PLAYING, PAUSED, ERROR }

fun interface SoundPlayerListener {
    fun playerChanged(player: SoundPlayer)

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<SoundPlayerListener> = Topic.create("Minecraft sound player", SoundPlayerListener::class.java)
    }
}

@Service(Service.Level.APP)
class SoundPlayer : Disposable {

    var event: SoundEvent? = null
        private set
    var variantIndex: Int = 0
        private set
    var sound: DecodedSound? = null
        private set
    var state: PlayerState = PlayerState.EMPTY
        private set
    var errorMessage: String? = null
        private set

    val variant: SoundFile? get() = event?.files?.getOrNull(variantIndex)
    val isPlaying: Boolean get() = state == PlayerState.PLAYING

    private val settings get() = McSoundsSettings.getInstance()
    private val loadGeneration = AtomicInteger()
    private var worker: Worker? = null
    private var idlePosition = 0L
    private val decodedCache = object : LinkedHashMap<String, DecodedSound>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecodedSound>?): Boolean =
            values.sumOf { it.pcm.size.toLong() } > DECODED_CACHE_BYTES && size > 1
    }

    fun open(event: SoundEvent, variant: Int = 0, play: Boolean = true) {
        stopWorker()
        this.event = event
        this.variantIndex = variant.coerceIn(0, event.files.lastIndex)
        load(play)
    }

    fun selectVariant(index: Int, play: Boolean = true) {
        val e = event ?: return
        val wrapped = Math.floorMod(index, e.files.size)
        stopWorker()
        variantIndex = wrapped
        load(play)
    }

    fun nextVariant() = selectVariant(variantIndex + 1)
    fun previousVariant() = selectVariant(variantIndex - 1)

    fun randomVariant() {
        val e = event ?: return
        if (e.files.size < 2) return selectVariant(0)
        val candidates = e.files.indices.filter { it != variantIndex }
        val total = candidates.sumOf { e.files[it].weight.coerceAtLeast(1) }
        var roll = Random.nextInt(total)
        for (i in candidates) {
            roll -= e.files[i].weight.coerceAtLeast(1)
            if (roll < 0) return selectVariant(i)
        }
        selectVariant(candidates.last())
    }

    fun play() {
        val s = sound ?: return
        when (state) {
            PlayerState.PLAYING -> return
            PlayerState.PAUSED -> worker?.let { it.paused = false; setState(PlayerState.PLAYING); return }
            PlayerState.READY -> Unit
            else -> return
        }
        val start = if (idlePosition >= s.frameCount) 0 else idlePosition
        worker = Worker(s, start).also { it.start() }
        setState(PlayerState.PLAYING)
    }

    fun pause() {
        val w = worker ?: return
        if (state != PlayerState.PLAYING) return
        w.paused = true
        setState(PlayerState.PAUSED)
    }

    fun togglePlayPause() {
        when (state) {
            PlayerState.PLAYING -> pause()
            PlayerState.PAUSED, PlayerState.READY -> play()
            PlayerState.ERROR -> event?.let { open(it, variantIndex, play = true) }
            else -> Unit
        }
    }

    fun replay() {
        if (sound == null) {
            event?.let { open(it, variantIndex, play = true) }
            return
        }
        stopWorker()
        idlePosition = 0
        setState(PlayerState.READY)
        play()
    }

    fun stop() {
        if (sound == null) return
        stopWorker()
        idlePosition = 0
        if (state == PlayerState.PLAYING || state == PlayerState.PAUSED) setState(PlayerState.READY) else fire()
    }

    fun seek(frame: Long) {
        val s = sound ?: return
        val target = frame.coerceIn(0, s.frameCount)
        val w = worker
        if (w != null) w.seekTo = target else idlePosition = target
        fire()
    }

    fun seekRelative(seconds: Double) {
        val s = sound ?: return
        seek(positionFrames() + (seconds * s.sampleRate).toLong())
    }

    fun positionFrames(): Long = worker?.position() ?: idlePosition

    fun volumeChanged() {
        worker?.gain = effectiveGain()
    }

    fun pitchChanged() {
        worker?.reanchor = true
    }

    override fun dispose() {
        stopWorker()
    }

    private fun load(play: Boolean) {
        val e = event ?: return
        val file = e.files[variantIndex]
        val gen = loadGeneration.incrementAndGet()
        sound = null
        idlePosition = 0
        errorMessage = null
        setState(PlayerState.LOADING)

        AppExecutorUtil.getAppExecutorService().execute {
            val result = runCatching {
                val hash = file.localPath?.let { path ->
                    "local:$path:${java.io.File(path).lastModified()}"
                } ?: SoundCatalogService.getInstance().catalog?.hashOf(file)
                synchronized(decodedCache) { hash?.let { decodedCache[it] } }
                    ?: DecodedSound.decodeOgg(SoundCatalogService.getInstance().getAudioBytes(file)).also { d ->
                        if (hash != null) synchronized(decodedCache) { decodedCache[hash] = d }
                    }
            }
            ApplicationManager.getApplication().invokeLater({
                if (gen != loadGeneration.get()) return@invokeLater
                result.onSuccess {
                    sound = it
                    setState(PlayerState.READY)
                    if (play) play()
                }.onFailure {
                    LOG.warn("Unable to load ${file.name}", it)
                    errorMessage = it.message ?: it.javaClass.simpleName
                    setState(PlayerState.ERROR)
                }
            }, ModalityState.any())
        }
    }

    private fun stopWorker() {
        worker?.let {
            it.running = false
            idlePosition = it.position()
        }
        worker = null
    }

    private fun onWorkerFinished(w: Worker, error: Throwable?) {
        ApplicationManager.getApplication().invokeLater({
            if (worker !== w) return@invokeLater
            worker = null
            idlePosition = 0
            if (error != null) {
                errorMessage = if (error is LineUnavailableException || error is IllegalArgumentException)
                    McSoundsBundle.message("error.audio.device") else error.message
                setState(PlayerState.ERROR)
            } else setState(PlayerState.READY)
        }, ModalityState.any())
    }

    private fun setState(newState: PlayerState) {
        state = newState
        fire()
    }

    private fun fire() {
        ApplicationManager.getApplication().messageBus.syncPublisher(SoundPlayerListener.TOPIC).playerChanged(this)
    }

    private fun effectivePitch(): Double {
        val user = settings.pitchPercent / 100.0
        val file = if (settings.applyFileAttributes) (variant?.pitch ?: 1f).toDouble() else 1.0
        return (user * file).coerceIn(0.25, 4.0)
    }

    private fun effectiveGain(): Float {
        val v = settings.volume / 100.0
        val curve = v.pow(1.8)
        val file = if (settings.applyFileAttributes) (variant?.volume ?: 1f).toDouble() else 1.0
        return (curve * file).toFloat().coerceIn(0f, 1f)
    }

    private inner class Worker(private val sound: DecodedSound, startFrame: Long) : Thread("Minecraft Sound Player") {
        @Volatile var running = true
        @Volatile var paused = false
        @Volatile var seekTo = -1L
        @Volatile var reanchor = false

        @Volatile private var line: SourceDataLine? = null
        private var srcPos = startFrame.toDouble()
        @Volatile private var step = effectivePitch()
        @Volatile var gain = effectiveGain()
        private var written = 0L

        private val segments = ArrayDeque<Pair<Long, Double>>()
        private val segLock = Any()

        init { isDaemon = true }

        fun position(): Long {
            val l = line ?: return srcPos.toLong().coerceIn(0, sound.frameCount)
            val played = l.longFramePosition
            synchronized(segLock) {
                val seg = segments.lastOrNull { it.first <= played } ?: segments.firstOrNull() ?: return 0
                return (seg.second + (played - seg.first) * step).toLong().coerceIn(0, sound.frameCount)
            }
        }

        override fun run() {
            var error: Throwable? = null
            val format = sound.format
            val l = try {
                AudioSystem.getSourceDataLine(format).also {
                    val bufferFrames = (format.sampleRate * BUFFER_SECONDS).toInt()
                    it.open(format, bufferFrames * format.frameSize)
                }
            } catch (t: Throwable) {
                onWorkerFinished(this, t)
                return
            }
            line = l
            try {
                anchor(l, srcPos)
                l.start()
                val chunkFrames = (format.sampleRate * CHUNK_SECONDS).toInt().coerceAtLeast(64)
                val out = ByteArray(chunkFrames * format.frameSize)
                var drainingSince = 0L
                while (running) {
                    val seek = seekTo
                    if (seek >= 0) {
                        seekTo = -1
                        l.flush()
                        srcPos = seek.toDouble()
                        anchor(l, srcPos)
                        drainingSince = 0
                    }
                    if (reanchor) {
                        reanchor = false
                        val current = position().toDouble()
                        step = effectivePitch()
                        gain = effectiveGain()
                        l.flush()
                        srcPos = current
                        anchor(l, srcPos)
                    }
                    if (paused) {
                        if (l.isRunning) l.stop()
                        sleep(10)
                        continue
                    } else if (!l.isRunning) l.start()

                    if (srcPos >= sound.frameCount) {
                        if (settings.loop) {
                            srcPos = 0.0
                            synchronized(segLock) { segments.addLast(written to 0.0) }
                            continue
                        }
                        if (drainingSince == 0L) drainingSince = System.currentTimeMillis()
                        val done = l.longFramePosition >= written ||
                            System.currentTimeMillis() - drainingSince > (BUFFER_SECONDS * 1000 + 400).toLong()
                        if (done) break
                        sleep(5)
                        continue
                    }

                    val availableFrames = l.available() / format.frameSize
                    if (availableFrames < chunkFrames / 2) {
                        sleep(4)
                        continue
                    }
                    val n = render(out, min(availableFrames, chunkFrames))
                    if (n > 0) {
                        l.write(out, 0, n * format.frameSize)
                        written += n
                    }
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                LOG.warn("Playback failed", t)
                error = t
            } finally {
                try { l.stop(); l.close() } catch (_: Throwable) {}
                line = null
                if (running) onWorkerFinished(this, error)
            }
        }

        private fun anchor(l: SourceDataLine, src: Double) {
            written = l.longFramePosition
            synchronized(segLock) {
                segments.clear()
                segments.addLast(written to src)
            }
        }

        private fun render(out: ByteArray, maxFrames: Int): Int {
            val pcm = sound.pcm
            val channels = sound.channels
            val fs = sound.frameSize
            val last = sound.frameCount - 1
            val g = gain
            val st = step
            var produced = 0
            while (produced < maxFrames && srcPos < sound.frameCount) {
                val i0 = floor(srcPos).toLong()
                val i1 = if (i0 + 1 > last) last else i0 + 1
                val frac = (srcPos - i0).toFloat()
                for (c in 0 until channels) {
                    val a = sample(pcm, (i0 * fs + c * 2).toInt())
                    val b = sample(pcm, (i1 * fs + c * 2).toInt())
                    val v = ((a + (b - a) * frac) * g).toInt().coerceIn(-32768, 32767)
                    val o = produced * fs + c * 2
                    out[o] = v.toByte()
                    out[o + 1] = (v shr 8).toByte()
                }
                produced++
                srcPos += st
            }
            return produced
        }

        private fun sample(pcm: ByteArray, i: Int): Float =
            ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toFloat()
    }

    companion object {
        private val LOG = logger<SoundPlayer>()
        private const val BUFFER_SECONDS = 0.12f
        private const val CHUNK_SECONDS = 0.02f
        private const val DECODED_CACHE_BYTES = 96L * 1024 * 1024

        fun getInstance(): SoundPlayer = ApplicationManager.getApplication().getService(SoundPlayer::class.java)
    }
}
