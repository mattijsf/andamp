// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import nl.mattix.andamp.backend.media3.dsp.BuiltInStages
import nl.mattix.andamp.backend.media3.dsp.DspStage
import nl.mattix.andamp.backend.media3.dsp.RackSmoothing
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.RackSlot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Winamp's DSP/Effect slot, holding the rack.
 *
 * The rack is an ordered list and the order is the signal's: the first slot
 * sees the audio first. A slot that is off is skipped, and a stage survives a
 * reorder so moving an effect does not drop its tail.
 *
 * One processor holds the whole rack, so adding or reordering an effect does
 * not rebuild ExoPlayer's chain: the audio thread reads one immutable list
 * from a volatile field.
 *
 * Changes are made without a click. Values glide to where the slider is
 * ([RackSmoothing]). A change to which effects run, or to their order, cannot
 * glide, so the rack fades to the dry signal over [FADE_MS], swaps the
 * arrangement there, and fades back.
 */
class DspAudioProcessor(
    /**
     * Where a plug-in's Lua runs: not the audio thread and not the one that
     * configures a stream, so a stream does not wait for a script to load.
     * By default a thread that ends itself when idle; see [pluginLoader].
     */
    private val loader: java.util.concurrent.Executor = pluginLoader(),
) : BaseAudioProcessor() {
    /**
     * Told when the list of effects changes, which happens when a plug-in's
     * script finishes loading on the worker.
     */
    @Volatile private var onCatalogue: (List<nl.mattix.andamp.core.model.EffectSpec>) -> Unit = {}

    /** Sets where the list is published, and publishes the current one if there is one. */
    fun publishCatalogue(to: (List<nl.mattix.andamp.core.model.EffectSpec>) -> Unit) {
        onCatalogue = to
        if (catalogue.isNotEmpty()) to(catalogue)
    }

    private val smoothing = RackSmoothing()

    /**
     * One stage per plug-in, kept across reorders so a move does not drop a tail.
     *
     * Written under [arranging] from whichever thread changes the rack, and
     * read on the audio thread at every control tick, hence the concurrent map.
     */
    private val stages = ConcurrentHashMap<String, DspStage>()

    /**
     * Held, one at a time, by whoever is changing what the rack is made of.
     *
     * Never held by [queueInput]: the audio thread receives its arrangement
     * through [chain] and [pendingChain].
     */
    private val arranging = Any()

    /** The stages to run, in rack order, rebuilt whenever the rack changes. */
    @Volatile private var chain: List<DspStage> = emptyList()

    private var channels = 0
    private var sampleRate = 0
    private var encoding = C.ENCODING_PCM_16BIT

    /**
     * Whether the peak limiter after the rack is wanted; see [RackSettings.limiter].
     *
     * Kept here because the rack's settings arrive here; the stage that acts
     * on it, [OutputAudioProcessor], reads it.
     */
    @Volatile var limiter: Boolean = false
        private set

    /** What the effects on offer declare: which controls jump, and which are structural. */
    @Volatile private var catalogue: List<nl.mattix.andamp.core.model.EffectSpec> = emptyList()

    /** What this stream can run. It grows when a plug-in's script has finished loading. */
    fun effectsOnOffer(): List<nl.mattix.andamp.core.model.EffectSpec> = catalogue

    /** The Lua sources of the plug-ins the listener installed. */
    @Volatile private var installed: List<String> = emptyList()
    private var frame = FloatArray(0)
    private var dry = FloatArray(0)

    /**
     * The arrangement waiting for the bottom of the fade.
     *
     * The audio thread takes and empties it in one step, so one set while the
     * last is being taken waits for the next fade.
     */
    private val pendingChain = AtomicReference<List<DspStage>?>(null)

    /**
     * How much of the rack is being heard, and which way that is moving.
     *
     * Written only by the audio thread. A change of arrangement asks for a
     * fade by setting [pendingChain] and nothing else.
     */
    private var fade = 1f
    private var fadeStep = 1f
    private var fading = 0
    private var untilTick = 0

    fun update(rack: RackSettings) =
        synchronized(arranging) {
            arrange(rack)
        }

    private fun arrange(rack: RackSettings) {
        limiter = rack.limiter
        val structural = structureOf(rack) != structureOf(smoothing.target)
        // a stage whose shape changed cannot be retuned: it is dropped here so
        // buildChain makes it again, and the fade below covers its tail
        if (structural) dropRebuilt(rack)
        smoothing.aim(rack)
        val next = buildChain()
        if (structural) {
            // built here, off the audio thread, which swaps it in at the bottom
            // of the fade
            pendingChain.set(next)
        } else {
            chain = next
        }
    }

    /**
     * The plug-ins the listener has installed, beside the bundled ones.
     *
     * The catalogue is loaded again for the new list, and a changed
     * arrangement goes through the same fade as switching an effect on.
     */
    fun setPlugins(sources: List<String>) =
        synchronized(arranging) {
            install(sources)
        }

    private fun install(sources: List<String>) {
        if (sources == installed) return
        installed = sources
        if (sampleRate == 0) {
            // nothing is playing, so there is no format to build for. Load for
            // a common format so the rack can list the effects; a real stream
            // loads for its own.
            offer(DEFAULT_RATE, DEFAULT_CHANNELS)
            return
        }
        prepare()
    }

    /** Loads a format's plug-ins on the worker and says what the rack can offer afterwards. */
    private fun offer(
        rate: Int,
        count: Int,
    ) {
        val sources = installed
        loader.execute {
            BuiltInStages.preload(rate, count, sources)
            if (sources == installed) onCatalogue(BuiltInStages.catalogue(rate, count, sources))
        }
    }

    /**
     * Makes sure this format's plug-ins are loaded, and takes what has arrived.
     *
     * The loading runs on [loader]. When it finishes the chain is built
     * again, and a changed one goes through the same fade as switching an
     * effect on.
     */
    private fun prepare() {
        val rate = sampleRate
        val count = channels
        val sources = installed
        if (!BuiltInStages.ready(rate, count, sources)) {
            loader.execute {
                BuiltInStages.preload(rate, count, sources)
                // the format may have moved on while the Lua ran
                if (rate == sampleRate && count == channels && sources == installed) take()
            }
        }
        take()
    }

    private fun take() =
        synchronized(arranging) {
            arrangeAgain()
        }

    private fun arrangeAgain() {
        catalogue = BuiltInStages.catalogue(sampleRate, channels, installed)
        onCatalogue(catalogue)
        smoothing.configure(sampleRate, catalogue)
        val next = buildChain()
        // Fade only when what runs changed. A script that finishes loading
        // changes the catalogue but not the chain when none of its effects are
        // switched on, and a fade then would drop the rack to dry for nothing.
        if (next == chain) {
            chain = next
            return
        }
        stages.keys.retainAll(
            smoothing.current.slots
                .map { it.pluginId }
                .toSet(),
        )
        pendingChain.set(next)
    }

    /**
     * What cannot glide: which plug-ins run, in what order, and the shape each
     * of them is in.
     *
     * A structural control chooses which nodes exist, so moving one is handled
     * like switching an effect on: the rack fades between the arrangements.
     */
    private fun structureOf(rack: RackSettings): List<String> =
        rack.slots.filter { it.enabled }.map { "${it.pluginId}${shapeOf(it)}" }

    /** The structural values of one slot, or nothing when it has none. */
    private fun shapeOf(slot: RackSlot): String {
        val spec = catalogue.firstOrNull { it.id == slot.pluginId } ?: return ""
        return spec.params
            .filter { it.structural }
            .joinToString(",") { "${it.id}=${slot.params[it.id, it.default]}" }
    }

    /** Forgets the stages whose shape the new rack changes, so they are built again. */
    private fun dropRebuilt(rack: RackSettings) {
        val was = smoothing.target
        rack.slots.forEach { slot ->
            val before = was[slot.pluginId] ?: return@forEach
            if (shapeOf(before) != shapeOf(slot)) stages.remove(slot.pluginId)
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!Pcm.speaks(inputAudioFormat.encoding)) {
            return AudioProcessor.AudioFormat.NOT_SET // bypass, like the EQ and balance
        }
        synchronized(arranging) { configureFor(inputAudioFormat) }
        return inputAudioFormat
    }

    private fun configureFor(inputAudioFormat: AudioProcessor.AudioFormat) {
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        frame = FloatArray(channels)
        dry = FloatArray(channels)
        // what glides and what jumps is declared per control, in the catalogue
        catalogue = BuiltInStages.catalogue(sampleRate, channels, installed)
        smoothing.configure(sampleRate, catalogue)
        // a new stream starts at the slider values
        smoothing.settle()
        // plug-ins not loaded for this format arrive later; the stream does not
        // wait for their Lua
        if (!BuiltInStages.ready(sampleRate, channels, installed)) {
            val rate = sampleRate
            val count = channels
            val sources = installed
            loader.execute {
                BuiltInStages.preload(rate, count, sources)
                if (rate == sampleRate && count == channels && sources == installed) take()
            }
        }
        fadeStep = 1f / (sampleRate * FADE_MS / MS_PER_SEC)
        fade = 1f
        fading = 0
        // the stages size their buffers from the rate, so a format change
        // builds a fresh set
        stages.clear()
        rebuildChain()
    }

    /**
     * Builds the ordered list of live stages, so the audio thread reads one
     * immutable list and a reorder is a reference swap.
     */
    private fun rebuildChain() {
        chain = buildChain()
        pendingChain.set(null)
    }

    private fun buildChain(): List<DspStage> {
        if (sampleRate == 0) return emptyList()
        return smoothing.current.slots.filter { it.enabled }.mapNotNull { slot ->
            // the shape comes from the slider positions, not from the smoothed
            // values: a structural control does not glide
            val shape = smoothing.target[slot.pluginId]?.params ?: slot.params
            val stage =
                stages[slot.pluginId]
                    ?: BuiltInStages
                        .create(slot.pluginId, sampleRate, channels, shape, installed)
                        ?.also { stages[slot.pluginId] = it }
                    ?: return@mapNotNull null
            BuiltInStages.apply(stage, slot.params)
            stage
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // an empty input is the pipeline draining; replaceOutputBuffer(0) would
        // return the shared EMPTY_BUFFER that was passed in (see BalanceAudioProcessor)
        if (!inputBuffer.hasRemaining()) return
        var active = chain
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val idle = active.isEmpty() && fading == 0 && pendingChain.get() == null
        if (idle || channels == 0) {
            output.put(inputBuffer)
        } else {
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val width = Pcm.bytesPerSample(encoding)
            while (inputBuffer.remaining() >= channels * width) {
                if (untilTick-- <= 0) {
                    tick()
                    untilTick = smoothing.controlPeriod
                }
                for (channel in 0 until channels) {
                    frame[channel] = Pcm.read(inputBuffer, encoding)
                    dry[channel] = frame[channel]
                }
                active.forEach { it.process(frame) }
                active = advanceFade(active)
                for (channel in 0 until channels) {
                    Pcm.write(output, encoding, dry[channel] + (frame[channel] - dry[channel]) * fade)
                }
            }
            // a partial frame at the buffer's end: its bytes pass through and
            // are not dropped
            while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        }
        output.flip()
    }

    /**
     * Moves the fade along by one frame, swapping in the waiting arrangement at
     * the bottom, and returns the arrangement to run from here.
     */
    private fun advanceFade(active: List<DspStage>): List<DspStage> {
        // an arrangement is waiting: fade down to dry, from wherever the fade is
        if (pendingChain.get() != null && fading != DUCKING && fade > 0f) fading = DUCKING
        if (fading == 0) return active
        fade += fading * fadeStep
        var running = active
        if (fading == RISING) {
            if (fade >= 1f) {
                fade = 1f
                fading = 0
            }
        } else if (fade <= 0f) {
            fade = 0f
            fading = RISING
            pendingChain.getAndSet(null)?.let {
                chain = it
                running = it
            }
        }
        return running
    }

    /** One control tick: walk the values on and hand the moved ones to their stages. */
    private fun tick() {
        if (!smoothing.tick()) return
        smoothing.current.slots.forEach { slot ->
            if (slot.enabled) stages[slot.pluginId]?.let { BuiltInStages.apply(it, slot.params) }
        }
    }

    override fun onFlush() {
        synchronized(arranging) {
            // a seek or a track change must not drag the old tail into the new audio;
            // every stage, not only the running ones, so a slot switched on later starts clean
            stages.values.forEach { it.reset() }
            // nothing to glide from across a seek, so the values land on the sliders
            smoothing.settle()
            rebuildChain()
        }
        untilTick = 0
        fade = 1f
        fading = 0
    }

    private companion object {
        /** The format assumed for listing the effects while nothing is playing. */
        const val DEFAULT_RATE = 44_100
        const val DEFAULT_CHANNELS = 2

        /** The length of the fade to dry and of the fade back, in milliseconds. */
        const val FADE_MS = 20f
        const val MS_PER_SEC = 1000f

        const val DUCKING = -1
        const val RISING = 1
    }
}

/** How long the loader thread stays alive with nothing to do, in milliseconds. */
private const val LOADER_IDLE_MS = 30_000L

/**
 * The thread a plug-in's Lua runs on, which ends itself once it has had
 * nothing to do for [idleMs].
 *
 * There is one per processor, and processors come and go, so a thread that
 * waited forever would outlive its processor. Scripts run one at a time, in
 * the order they were asked for.
 */
internal fun pluginLoader(idleMs: Long = LOADER_IDLE_MS): ThreadPoolExecutor =
    ThreadPoolExecutor(
        1,
        1,
        idleMs,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(),
        ThreadFactory { work -> Thread(work, "dsp-plugins").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }
