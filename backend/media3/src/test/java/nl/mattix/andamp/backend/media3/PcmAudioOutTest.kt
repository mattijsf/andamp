// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.PcmProvider
import nl.mattix.andamp.core.playback.PcmRingBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A backend's own samples on their way out: the render loop, the device, and
 * the audio focus, headphones and wake locks while it sounds.
 *
 * The device is an `AudioTrack` subclass that records the level it was set to
 * and the bytes written to it, and answers the playback head and the timestamp
 * a case gives it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PcmAudioOutTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val audio = app.getSystemService(AudioManager::class.java)
    private val built = mutableListOf<Device>()
    private val heard = mutableListOf<AudioOut.Interruption>()

    private class Device :
        AudioTrack(
            musicAttributes(),
            AudioFormat
                .Builder()
                .setSampleRate(PcmProvider.SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            PACKET_BYTES,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        ) {
        @Volatile var gain = Float.NaN
        val written = LinkedBlockingQueue<ByteArray>()
        val released = CountDownLatch(1)

        /** The frames it says it has played; a flush sets it back to 0, as a device's does. */
        @Volatile var head = 0

        /** The frame its timestamp says is at the speaker now, or -1 for a device that gives no timestamp. */
        @Volatile var atSpeaker = -1L

        override fun getPlaybackHeadPosition(): Int = head

        override fun getTimestamp(timestamp: AudioTimestamp): Boolean {
            if (atSpeaker < 0) return false
            timestamp.framePosition = atSpeaker
            timestamp.nanoTime = System.nanoTime()
            return true
        }

        override fun flush() {
            head = 0
        }

        override fun setVolume(gain: Float): Int {
            this.gain = gain
            return AudioTrack.SUCCESS
        }

        override fun write(
            audioData: ByteArray,
            offsetInBytes: Int,
            sizeInBytes: Int,
            writeMode: Int,
        ): Int {
            written += audioData.copyOfRange(offsetInBytes, offsetInBytes + sizeInBytes)
            return sizeInBytes
        }

        override fun release() {
            released.countDown()
        }

        fun nextWrite(): ByteArray = checkNotNull(written.poll(5, TimeUnit.SECONDS)) { "a write reaches the device" }
    }

    /** Packets handed over one at a time, with a wait of up to two seconds for the next; an empty one is the end. */
    private class Feed : PcmProvider {
        val waiting = LinkedBlockingQueue<ByteArray>()

        override fun read(into: ByteArray): Int {
            val packet = waiting.poll(2, TimeUnit.SECONDS) ?: return 0
            if (packet.isEmpty()) return -1
            packet.copyInto(into)
            return packet.size
        }
    }

    /** The clock the tap moves the heard place on with; it stands still unless a case moves it. */
    @Volatile private var nowNanos = 0L

    private fun outOver(context: Context? = app) =
        PcmAudioOut(context, PcmChain(ring = PcmRingBuffer(nanoTime = { nowNanos }))).apply {
            openDevice = { Device().also { built += it } }
            setInterruptions { heard += it }
        }

    /** Hands over [packets] packets of [PACKET_FRAMES] frames each, and waits for the device to have them. */
    private fun Feed.play(
        device: Device,
        packets: Int,
    ) = repeat(packets) {
        waiting += tone()
        device.nextWrite()
    }

    /**
     * The distance the tap reports, once it is [expected] give or take [slack]. The render
     * thread reports after a write has landed, so the answer can be a moment behind the write.
     */
    private fun PcmAudioOut.aheadSettlesAt(
        expected: Long,
        slack: Long = 0,
    ): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val ahead = checkNotNull(tap).aheadSamples
            if (ahead in expected - slack..expected + slack) return ahead
            Thread.sleep(2)
        }
        return checkNotNull(tap).aheadSamples
    }

    /** A sawtooth on both channels, [frames] long; a packet is never more than the loop reads at once. */
    private fun tone(frames: Int = PACKET_BYTES / PcmProvider.BYTES_PER_FRAME) =
        ByteArray(frames * PcmProvider.BYTES_PER_FRAME) { at ->
            // little-endian 16-bit: the high byte carries the wave
            if (at % 2 == 1) ((at / 4) % 64 - 32).toByte() else 0.toByte()
        }

    private fun focusChange(change: Int) {
        shadowOf(audio).lastAudioFocusRequest.listener.onAudioFocusChange(change)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a level set before anything plays is the level it plays at, every time`() {
        val out = outOver(context = null)
        out.setVolume(0.2f)

        out.start(Feed())
        out.stop()
        out.start(Feed())
        out.stop()

        assertEquals("every device opens at the level set before playback", listOf(0.2f, 0.2f), built.map { it.gain })
    }

    @Test
    fun `a level set while playing moves the device that is playing`() {
        val out = outOver(context = null)
        out.start(Feed())

        out.setVolume(0.5f)
        out.stop()

        assertEquals(0.5f, built.single().gain)
    }

    @Test
    fun `it asks for the audio as music, and gives it back on stop`() {
        val out = outOver()

        out.start(Feed())
        out.stop()

        val asked = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioAttributes.USAGE_MEDIA, asked.audioAttributes.usage)
        assertNotNull("the audio focus is given back on stop", shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `without a context it requests no audio focus`() {
        val out = outOver(context = null)

        out.start(Feed())
        out.stop()

        assertNull(shadowOf(audio).lastAudioFocusRequest)
    }

    @Test
    fun `another app taking the audio for good pauses the owner`() {
        val out = outOver()
        out.start(Feed())

        focusChange(AudioManager.AUDIOFOCUS_LOSS)
        out.stop()

        assertEquals(listOf(AudioOut.Interruption.PAUSE), heard)
    }

    /**
     * A pause during a transient loss keeps the focus request, so the focus
     * can be handed back when the call ends.
     */
    @Test
    fun `a call pauses it for now, and the end of the call says to carry on`() {
        val out = outOver()
        out.start(Feed())

        focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        out.pause()
        assertNull("a pause for a call keeps the audio focus", shadowOf(audio).lastAbandonedAudioFocusRequest)
        focusChange(AudioManager.AUDIOFOCUS_GAIN)
        out.stop()

        assertEquals(listOf(AudioOut.Interruption.PAUSE_FOR_NOW, AudioOut.Interruption.RESUME), heard)
    }

    @Test
    fun `a pause the listener pressed gives the audio back`() {
        val out = outOver()
        out.start(Feed())

        out.pause()

        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        out.stop()
    }

    @Test
    fun `playing during a call is refused, and the owner told to pause`() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val out = outOver()

        out.start(Feed())
        shadowOf(Looper.getMainLooper()).idle()
        out.stop()

        assertEquals(listOf(AudioOut.Interruption.PAUSE), heard)
    }

    @Test
    fun `a navigation prompt lowers the level for a moment, and pauses nothing`() {
        val out = outOver()
        out.setVolume(0.5f)
        out.start(Feed())
        val device = built.single()

        focusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        val ducked = device.gain
        focusChange(AudioManager.AUDIOFOCUS_GAIN)
        out.stop()

        assertEquals(0.1f, ducked, 0.0001f)
        assertEquals(0.5f, device.gain, 0.0001f)
        assertEquals(emptyList<AudioOut.Interruption>(), heard)
    }

    @Test
    fun `headphones coming out pause the owner`() {
        val out = outOver()
        out.start(Feed())

        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        out.stop()

        assertEquals(listOf(AudioOut.Interruption.PAUSE), heard)
    }

    @Test
    fun `headphones coming out while paused report nothing`() {
        val out = outOver()
        out.start(Feed())
        out.pause()

        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        out.stop()

        assertEquals(emptyList<AudioOut.Interruption>(), heard)
    }

    /**
     * The wake and Wi-Fi locks are held from start to pause or stop, which
     * covers the gap between two tracks.
     */
    @Test
    fun `the phone stays awake while it plays, and only then`() {
        val out = outOver()
        val wifi = shadowOf(app.getSystemService(WifiManager::class.java))

        out.start(Feed())
        val wake = ShadowPowerManager.getLatestWakeLock()
        val playing = wake.isHeld to wifi.activeLockCount
        out.pause()
        val paused = wake.isHeld to wifi.activeLockCount
        out.resume()
        val resumed = wake.isHeld to wifi.activeLockCount
        out.stop()

        assertEquals(true to 1, playing)
        assertEquals(false to 0, paused)
        assertEquals(true to 1, resumed)
        assertFalse(wake.isHeld)
        assertEquals(0, wifi.activeLockCount)
    }

    /**
     * A read can block, and nothing interrupts it. A start in that window must
     * not begin a second render thread beside the first, or the stale one
     * would write the new run's first packet to the released device.
     */
    @Test
    fun `a quick restart plays the new run's first packet on the new device`() {
        val out = outOver(context = null)
        val feed = Feed()
        out.start(feed)
        out.stop()
        out.start(feed)
        val packet = tone(512)

        feed.waiting += packet
        val landed = built[1].nextWrite()
        out.stop()

        assertArrayEquals(packet, landed)
        assertTrue("the stopped device is not written to", built[0].written.isEmpty())
    }

    @Test
    fun `a provider with no more to give leaves it able to start again`() {
        val out = outOver(context = null)
        out.start(Feed().apply { waiting += ByteArray(0) })
        assertTrue("the ended run releases its device", built[0].released.await(5, TimeUnit.SECONDS))

        val feed = Feed()
        out.start(feed)
        val packet = tone(64)
        feed.waiting += packet
        val landed = built[1].nextWrite()
        out.stop()

        assertArrayEquals(packet, landed)
    }

    /** A seek with a reverb on: its tail must not follow into the new position. */
    @Test
    fun `a discard leaves the reverb's tail behind`() {
        val out = outOver(context = null)
        out.setDsp(REVERB_ON)
        val feed = Feed()
        out.start(feed)
        val device = built.single()
        val silence = ByteArray(PACKET_BYTES)
        // past the rack's fade in and its controls' glide
        repeat(8) {
            feed.waiting += tone()
            device.nextWrite()
        }
        feed.waiting += silence
        val tail = device.nextWrite()
        feed.waiting += tone()
        device.nextWrite()

        out.discard()
        feed.waiting += silence
        val after = device.nextWrite()
        out.stop()

        assertTrue("the reverb leaves a tail before the discard", tail.any { it != 0.toByte() })
        assertTrue("no tail of the old audio comes through the discard", after.all { it == 0.toByte() })
    }

    @Test
    fun `the tap is told how far the device is behind what it was handed`() {
        val out = outOver(context = null)
        val feed = Feed()
        out.start(feed)
        val device = built.single()
        device.head = 2_000

        feed.play(device, packets = 3)
        val ahead = out.aheadSettlesAt(3L * PACKET_FRAMES - 2_000)
        out.stop()

        assertEquals(3L * PACKET_FRAMES - 2_000, ahead)
    }

    @Test
    fun `a device that says what is at the speaker is measured to there`() {
        val out = outOver(context = null)
        val feed = Feed()
        out.start(feed)
        val device = built.single()
        device.head = 3_000
        device.atSpeaker = 1_000

        feed.play(device, packets = 2)
        // the timestamp is a few microseconds old when it is read, which is a frame at most
        val ahead = out.aheadSettlesAt(2L * PACKET_FRAMES - 1_000, slack = FRAMES_OF_SLACK)
        out.stop()

        assertEquals((2L * PACKET_FRAMES - 1_000).toDouble(), ahead.toDouble(), FRAMES_OF_SLACK.toDouble())
    }

    /** A flush sets the device's playback head back to 0, and what was handed over before it is gone. */
    @Test
    fun `a discard starts the distance over`() {
        val out = outOver(context = null)
        val feed = Feed()
        out.start(feed)
        val device = built.single()
        feed.play(device, packets = 3)
        device.head = 5_000
        out.aheadSettlesAt(3L * PACKET_FRAMES)

        out.discard()
        feed.play(device, packets = 1)
        val ahead = out.aheadSettlesAt(PACKET_FRAMES.toLong())
        out.stop()

        assertEquals(PACKET_FRAMES.toLong(), ahead)
    }

    @Test
    fun `the distance stands still through a pause, and moves again after it`() {
        val out = outOver(context = null)
        val feed = Feed()
        out.start(feed)
        val device = built.single()
        feed.play(device, packets = 2)
        val playing = out.aheadSettlesAt(2L * PACKET_FRAMES)

        out.pause()
        // two packets, so the render thread is done with the first when the second has landed
        feed.play(device, packets = 2)
        nowNanos += TENTH_OF_A_SECOND_NANOS
        val paused = checkNotNull(out.tap).aheadSamples
        out.resume()
        val resumed = out.aheadSettlesAt(4L * PACKET_FRAMES)
        nowNanos += TENTH_OF_A_SECOND_NANOS
        val later = checkNotNull(out.tap).aheadSamples
        out.stop()

        assertEquals("a paused device is not moving toward what the tap holds", playing, paused)
        assertEquals("the packets handed over during the pause count once it plays", 4L * PACKET_FRAMES, resumed)
        assertEquals("a tenth of a second on, a tenth of a second is heard", resumed - 4_410, later)
    }

    private companion object {
        /** The size the render loop reads at a time, so one packet is one read and one write. */
        const val PACKET_BYTES = 16_384
        const val PACKET_FRAMES = PACKET_BYTES / PcmProvider.BYTES_PER_FRAME
        const val FRAMES_OF_SLACK = 50L
        const val TENTH_OF_A_SECOND_NANOS = 100_000_000L

        val REVERB_ON =
            RackSettings(listOf(RackSlot(BuiltInEffects.REVERB, enabled = true, params = BuiltInEffects.reverb.defaults)))
    }
}
