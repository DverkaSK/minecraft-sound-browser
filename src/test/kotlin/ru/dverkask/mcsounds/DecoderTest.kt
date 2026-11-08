package ru.dverkask.mcsounds

import org.junit.Assert.assertTrue
import org.junit.Test
import ru.dverkask.mcsounds.audio.DecodedSound
import java.util.concurrent.Executors

class DecoderTest {
    @Test
    fun decodesOggVorbis() {
        val bytes = javaClass.getResourceAsStream("/sample.ogg")!!.readBytes()
        val sound = DecodedSound.decodeOgg(bytes)
        assertTrue(sound.frameCount > 1000)
        assertTrue(sound.peaks.any { it > 0.5f })
    }

    @Test
    fun decodesConcurrently() {
        val bytes = javaClass.getResourceAsStream("/sample.ogg")!!.readBytes()
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..32).map { pool.submit<Long> { DecodedSound.decodeOgg(bytes).frameCount } }.map { it.get() }
        pool.shutdown()
        println("concurrent=" + results.distinct())
        assertTrue(results.all { it == results.first() })
    }

    @Test
    fun decodesShortSingleSegmentFile() {
        val sound = DecodedSound.decodeOgg(javaClass.getResourceAsStream("/short.ogg")!!.readBytes())
        assertTrue(sound.frameCount > 10000)
    }
}
