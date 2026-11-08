package ru.dverkask.mcsounds.audio

import com.jcraft.jogg.Packet
import com.jcraft.jogg.Page
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.sound.sampled.AudioFormat

internal object OggVorbisDecoder {

    fun decode(bytes: ByteArray): DecodedSound {
        val sync = SyncState()
        val stream = StreamState()
        val page = Page()
        val packet = Packet()
        val info = Info()
        val comment = Comment()
        val dsp = DspState()
        val block = Block(dsp)

        sync.init()
        val offset = sync.buffer(bytes.size)
        System.arraycopy(bytes, 0, sync.data, offset, bytes.size)
        sync.wrote(bytes.size)

        if (sync.pageout(page) != 1) throw IOException("Not an Ogg stream")
        stream.init(page.serialno())
        info.init()
        comment.init()
        if (stream.pagein(page) < 0 || stream.packetout(packet) != 1 || info.synthesis_headerin(comment, packet) < 0) {
            throw IOException("Not an Ogg Vorbis stream")
        }

        var headers = 0
        while (headers < 2) {
            val r = sync.pageout(page)
            if (r == 0) throw IOException("Truncated Vorbis header")
            if (r != 1) continue
            stream.pagein(page)
            while (headers < 2) {
                val p = stream.packetout(packet)
                if (p == 0) break
                if (p < 0) throw IOException("Corrupt Vorbis header")
                info.synthesis_headerin(comment, packet)
                headers++
            }
        }

        val channels = info.channels
        val out = ByteArrayOutputStream(bytes.size * 12)
        val chunk = ByteArray(8192 * channels * 2)
        dsp.synthesis_init(info)
        block.init(dsp)
        val pcmHolder = arrayOfNulls<Array<FloatArray>>(1)
        val index = IntArray(channels)

        fun drainPackets() {
            while (true) {
                val p = stream.packetout(packet)
                if (p == 0) return
                if (p < 0) continue
                if (block.synthesis(packet) == 0) dsp.synthesis_blockin(block)
                while (true) {
                    val samples = dsp.synthesis_pcmout(pcmHolder, index)
                    if (samples <= 0) break
                    val pcm = pcmHolder[0]!!
                    val n = minOf(samples, chunk.size / (channels * 2))
                    var o = 0
                    for (i in 0 until n) {
                        for (c in 0 until channels) {
                            val v = (pcm[c][index[c] + i] * 32767f).toInt().coerceIn(-32768, 32767)
                            chunk[o++] = v.toByte()
                            chunk[o++] = (v shr 8).toByte()
                        }
                    }
                    out.write(chunk, 0, o)
                    dsp.synthesis_read(n)
                }
            }
        }

        drainPackets()
        while (true) {
            val r = sync.pageout(page)
            if (r == 0) break
            if (r < 0) continue
            stream.pagein(page)
            drainPackets()
            if (page.eos() != 0) break
        }

        stream.clear()
        block.clear()
        dsp.clear()
        info.clear()
        sync.clear()

        val pcm = out.toByteArray()
        if (pcm.isEmpty()) throw IOException("The sound file contains no audio")
        val format = AudioFormat(info.rate.toFloat(), 16, channels, true, false)
        return DecodedSound(pcm, format)
    }
}
