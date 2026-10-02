// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs.author

import nl.mattix.andamp.visualizer.avs.AvsComponent

/*
 * The components AndAmpPack's presets are written with: one factory per
 * component, writing the fields in the order the module's readers read them.
 * A factory takes only the parameters the pack sets; the other fields are
 * written as constants.
 */

/** Super Scope (id 36): sections in file order perPoint, perFrame, onBeat, init. */
internal fun superScope(
    init: String,
    frame: String,
    beat: String,
    point: String,
    flags: Int,
    colours: List<Int>,
    drawMode: Int = 1,
): AvsComponent =
    AvsAuthor.builtin(
        SUPER_SCOPE,
        BodyWriter()
            .byte(1)
            .sizedString(point)
            .sizedString(frame)
            .sizedString(beat)
            .sizedString(init)
            .int32(flags)
            .colourList(colours)
            .int32(drawMode)
            .toByteArray(),
    )

/** Movement (id 15) running one of AVS's built-in warp expressions. */
internal fun movementBuiltin(
    effect: Int,
    fiftyFifty: Int = 1,
    wrap: Int = 0,
): AvsComponent =
    AvsAuthor.builtin(
        MOVEMENT,
        BodyWriter()
            .int32(effect)
            .int32(fiftyFifty)
            .int32(0) // sourceMapped: the reader skips it
            .int32(0) // cartesian: built-ins use their table's coordinates
            .int32(0) // bilinear: the reader skips it
            .int32(wrap)
            .toByteArray(),
    )

/** Movement (id 15) with its own script: the 0x7FFF marker, then the code. */
internal fun movementScript(
    code: String,
    cartesian: Int = 0,
    wrap: Int = 0,
): AvsComponent =
    AvsAuthor.builtin(
        MOVEMENT,
        BodyWriter()
            .int32(MOVEMENT_CUSTOM)
            .byte(1)
            .sizedString(code)
            .int32(1) // fiftyFifty
            .int32(0) // sourceMapped
            .int32(cartesian)
            .int32(1) // bilinear
            .int32(wrap)
            .toByteArray(),
    )

/** Dynamic Movement (id 43): four sections, six settings, and vis_avs's 2-int tail. */
@Suppress("LongParameterList") // the component's own dials, in its file order
internal fun dynamicMovement(
    init: String,
    frame: String,
    beat: String,
    point: String,
    coordinates: Int,
    gridWidth: Int,
    gridHeight: Int,
    wrap: Int = 0,
): AvsComponent =
    AvsAuthor.builtin(
        DYNAMIC_MOVEMENT,
        BodyWriter()
            .byte(1)
            .sizedString(point)
            .sizedString(frame)
            .sizedString(beat)
            .sizedString(init)
            .int32(0) // bilinear: the reader skips it
            .int32(coordinates)
            .int32(gridWidth)
            .int32(gridHeight)
            .int32(0) // blend
            .int32(wrap)
            .int32(0) // buffer: vis_avs's tail, which the reader does not read
            .int32(0) // alphaOnly
            .toByteArray(),
    )

/** Color Modifier (id 45): the channel-curve LUT builder. */
internal fun colorModifier(point: String): AvsComponent =
    AvsAuthor.builtin(
        COLOR_MODIFIER,
        BodyWriter()
            .byte(1)
            .sizedString(point)
            .sizedString("")
            .sizedString("")
            .sizedString("")
            .int32(0) // recomputeEveryFrame: the tables are built once
            .toByteArray(),
    )

/** Roto Blitter (id 9): zoom and rotate are file values, still at 31 and 32. */
@Suppress("LongParameterList") // the component's own dials, in its file order
internal fun rotoBlitter(
    zoom: Int,
    rotate: Int,
    onBeatReverse: Int,
    reversalSpeed: Int,
    beatZoom: Int,
    onBeat: Int,
): AvsComponent =
    AvsAuthor.builtin(
        ROTO_BLITTER,
        BodyWriter()
            .int32(zoom)
            .int32(rotate)
            .int32(1) // 50/50
            .int32(onBeatReverse)
            .int32(reversalSpeed)
            .int32(beatZoom)
            .int32(onBeat)
            .toByteArray(),
    )

/** Blitter Feedback (id 4): 32 is the identity zoom. */
internal fun blitterFeedback(
    zoom: Int,
    beatZoom: Int,
    onBeat: Int = 1,
): AvsComponent =
    AvsAuthor.builtin(
        BLITTER_FEEDBACK,
        BodyWriter()
            .int32(zoom)
            .int32(beatZoom)
            .int32(1) // 50/50
            .int32(onBeat)
            .toByteArray(),
    )

/** Blur (id 6). Levels in file order: 1 medium, 2 light, 3 heavy. */
internal fun blur(
    level: Int,
    roundUp: Int = 0,
): AvsComponent = AvsAuthor.builtin(BLUR, BodyWriter().int32(level).int32(roundUp).toByteArray())

/** FadeOut (id 3): every channel walks toward the target color. */
internal fun fadeOut(
    speed: Int,
    colour: Int = 0x000000,
): AvsComponent = AvsAuthor.builtin(FADE_OUT, BodyWriter().int32(speed).colour(colour).toByteArray())

/** Set Render Mode (id 40): one packed int; bit 31 is the enable. */
internal fun setRenderMode(
    blend: Int,
    lineSize: Int = 1,
): AvsComponent =
    AvsAuthor.builtin(
        SET_RENDER_MODE,
        BodyWriter().int32(blend or (lineSize shl 16) or (ENABLED_BYTE shl 24)).toByteArray(),
    )

/** Dot Grid (id 17): speeds are 1/256 px per frame, signed. */
internal fun dotGrid(
    colours: List<Int>,
    spacing: Int,
    speedX: Int,
    speedY: Int,
): AvsComponent =
    AvsAuthor.builtin(
        DOT_GRID,
        BodyWriter()
            .colourList(colours)
            .int32(spacing)
            .int32(speedX)
            .int32(speedY)
            .int32(1) // additive
            .toByteArray(),
    )

/** Ring (id 14): flags' bits 4-5 place it; 0x20 is the center. */
internal fun ring(
    colours: List<Int>,
    size: Int,
): AvsComponent =
    AvsAuthor.builtin(
        RING,
        BodyWriter()
            .int32(RING_CENTRE)
            .colourList(colours)
            .int32(size)
            .int32(0) // waveform source
            .toByteArray(),
    )

/** Timescope (id 39): the scrolling spectrogram, one column per frame. */
internal fun timescope(
    colour: Int,
    bands: Int,
): AvsComponent =
    AvsAuthor.builtin(
        TIMESCOPE,
        BodyWriter()
            .int32(1) // enabled
            .colour(colour)
            .int32(1) // pairBlend low: additive
            .int32(0) // pairBlend high
            .int32(0) // channel: this build's tap is mono
            .int32(bands)
            .toByteArray(),
    )

/** Starfield (id 27), additive; star count is density at 512x384 before area scaling. */
@Suppress("LongParameterList") // the component's own dials, in its file order
internal fun starfield(
    colour: Int,
    warpSpeed: Float,
    stars: Int,
    onBeat: Int,
    beatSpeed: Float,
    beatFrames: Int,
): AvsComponent =
    AvsAuthor.builtin(
        STARFIELD,
        BodyWriter()
            .int32(1) // enabled
            .colour(colour)
            .int32(1) // pairBlend low: additive
            .int32(0) // pairBlend high
            .float(warpSpeed)
            .int32(stars)
            .int32(onBeat)
            .float(beatSpeed)
            .int32(beatFrames)
            .toByteArray(),
    )

/** Moving Particle (id 8): flags bit 0 enables, bit 1 grows on beat. */
internal fun movingParticle(
    colour: Int,
    distance: Int,
    size: Int,
    beatSize: Int,
): AvsComponent =
    AvsAuthor.builtin(
        MOVING_PARTICLE,
        BodyWriter()
            .int32(PARTICLE_ON_AND_GROWS)
            .colour(colour)
            .int32(distance)
            .int32(size)
            .int32(beatSize)
            .int32(1) // additive
            .toByteArray(),
    )

/** Bass Spin (id 7), both arms, filled. */
internal fun bassSpin(
    colourLeft: Int,
    colourRight: Int,
): AvsComponent =
    AvsAuthor.builtin(
        BASS_SPIN,
        BodyWriter()
            .int32(BOTH_ARMS)
            .colour(colourLeft)
            .colour(colourRight)
            .int32(1) // filled triangles
            .toByteArray(),
    )

/** Grain (id 24), additive over the lit pixels only. */
internal fun grain(amount: Int): AvsComponent =
    AvsAuthor.builtin(
        GRAIN,
        BodyWriter()
            .int32(1) // enabled
            .int32(1) // additive (wins over fifty here)
            .int32(0)
            .int32(amount)
            .int32(0) // not static
            .toByteArray(),
    )

/**
 * Colorfade (id 11), enabled and on-beat, with the v2.81d swapped walk
 * (version bits zero). Each set is stored (2nd, max, 3rd/gray): the second
 * value drives the brightest channel.
 */
@Suppress("LongParameterList") // the component's own dials, in its file order
internal fun colorfade(
    fader2nd: Int,
    faderMax: Int,
    fader3rdGray: Int,
    beat2nd: Int,
    beatMax: Int,
    beat3rdGray: Int,
): AvsComponent =
    AvsAuthor.builtin(
        COLORFADE,
        BodyWriter()
            .int32(COLORFADE_ALL_ON)
            .int32(fader2nd)
            .int32(faderMax)
            .int32(fader3rdGray)
            .int32(beat2nd)
            .int32(beatMax)
            .int32(beat3rdGray)
            .toByteArray(),
    )

/** Color Clip (id 12) in below mode: everything darker than [against] goes to [replacement]. */
internal fun colorClipBelow(
    against: Int,
    replacement: Int = 0x000000,
): AvsComponent =
    AvsAuthor.builtin(
        COLOR_CLIP,
        BodyWriter()
            .int32(1) // below
            .colour(against)
            .colour(replacement)
            .int32(0) // level: near mode only
            .toByteArray(),
    )

/** Channel Shift (an APE): mode is a control id, 1018 = GBR. */
internal fun channelShift(
    mode: Int,
    onBeatRandom: Int,
): AvsComponent = AvsAuthor.ape("Channel Shift", BodyWriter().int32(mode).int32(onBeatRandom).toByteArray())

/** OnBeat Clear (id 5): blend 1 is 50/50, anything else replaces. */
internal fun onBeatClear(
    colour: Int,
    blend: Int,
    beats: Int,
): AvsComponent =
    AvsAuthor.builtin(
        ONBEAT_CLEAR,
        BodyWriter()
            .colour(colour)
            .int32(blend)
            .int32(beats)
            .toByteArray(),
    )

/** Interferences (id 41): N copies of the frame around a circle, summed. */
@Suppress("LongParameterList") // the component's own dials, in its file order
internal fun interferences(
    layers: Int,
    distance: Int,
    alpha: Int,
    rotation: Int,
    additive: Int,
    onBeatDistance: Int,
    onBeatAlpha: Int,
    onBeatRotation: Int,
    onBeatSpeed: Float,
): AvsComponent =
    AvsAuthor.builtin(
        INTERFERENCES,
        BodyWriter()
            .int32(1) // enabled
            .int32(layers)
            .int32(0) // initRotation
            .int32(distance)
            .int32(alpha)
            .int32(rotation)
            .int32(additive)
            .int32(0) // fifty
            .int32(onBeatDistance)
            .int32(onBeatAlpha)
            .int32(onBeatRotation)
            .int32(1) // separateRgb
            .int32(1) // onBeat
            .float(onBeatSpeed)
            .toByteArray(),
    )

private const val SUPER_SCOPE = 36
private const val MOVEMENT = 15
private const val MOVEMENT_CUSTOM = 0x7FFF
private const val DYNAMIC_MOVEMENT = 43
private const val COLOR_MODIFIER = 45
private const val ROTO_BLITTER = 9
private const val BLITTER_FEEDBACK = 4
private const val BLUR = 6
private const val FADE_OUT = 3
private const val SET_RENDER_MODE = 40
private const val DOT_GRID = 17
private const val RING = 14
private const val TIMESCOPE = 39
private const val STARFIELD = 27
private const val MOVING_PARTICLE = 8
private const val BASS_SPIN = 7
private const val GRAIN = 24
private const val COLORFADE = 11
private const val COLOR_CLIP = 12
private const val ONBEAT_CLEAR = 5
private const val INTERFERENCES = 41

private const val ENABLED_BYTE = 0x80
private const val RING_CENTRE = 0x20
private const val PARTICLE_ON_AND_GROWS = 0x03
private const val BOTH_ARMS = 0x03

/**
 * Enabled and on-beat, version bits zero for the swapped walk. The random bit
 * is left clear: random rolls can sum positive and push the frame toward
 * white between beats.
 */
private const val COLORFADE_ALL_ON = 0x05
