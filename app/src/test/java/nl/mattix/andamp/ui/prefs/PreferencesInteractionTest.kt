// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BuiltInEffects
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.core.player.PlayerFacade
import nl.mattix.andamp.state.DspOps
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.PluginOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The effects page: what it shows, and that touching it reaches the rack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PreferencesInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        /** Two groups, each with its own switch. */
        val TWO_GATES =
            nl.mattix.andamp.core.model.EffectSpec(
                id = "com.example.gates",
                name = "Gates",
                params =
                    listOf(
                        nl.mattix.andamp.core.model
                            .ParamSpec("first", "First", default = 1f, toggle = true),
                        nl.mattix.andamp.core.model
                            .ParamSpec("a", "A", default = 0.5f),
                        nl.mattix.andamp.core.model
                            .ParamSpec("second", "Second", default = 1f, toggle = true),
                        nl.mattix.andamp.core.model
                            .ParamSpec("b", "B", default = 0.5f),
                    ),
                ui =
                    listOf(
                        nl.mattix.andamp.core.model.UiNode.Group(
                            name = "One",
                            items =
                                listOf(
                                    nl.mattix.andamp.core.model.UiNode
                                        .Control(1, nl.mattix.andamp.core.model.UiNode.Kind.SLIDER),
                                ),
                            enabledBy = 0,
                        ),
                        nl.mattix.andamp.core.model.UiNode.Group(
                            name = "Two",
                            items =
                                listOf(
                                    nl.mattix.andamp.core.model.UiNode
                                        .Control(3, nl.mattix.andamp.core.model.UiNode.Kind.SLIDER),
                                ),
                            enabledBy = 2,
                        ),
                    ),
            )

        /** A plug-in that asked for a layout: a switched group with a slider and a line of text. */
        val GROUPED =
            nl.mattix.andamp.core.model.EffectSpec(
                id = "com.example.grouped",
                name = "Grouped",
                params =
                    listOf(
                        nl.mattix.andamp.core.model
                            .ParamSpec("on", "Switched in", default = 1f, toggle = true),
                        nl.mattix.andamp.core.model
                            .ParamSpec("size", "Size", default = 0.5f),
                    ),
                ui =
                    listOf(
                        nl.mattix.andamp.core.model.UiNode.Group(
                            name = "Room",
                            items =
                                listOf(
                                    nl.mattix.andamp.core.model.UiNode
                                        .Control(1, nl.mattix.andamp.core.model.UiNode.Kind.SLIDER, label = "How big"),
                                    nl.mattix.andamp.core.model.UiNode
                                        .Label("a line of its own"),
                                ),
                            enabledBy = 0,
                        ),
                    ),
                presets =
                    listOf(
                        nl.mattix.andamp.core.model
                            .Preset("Wide", mapOf("size" to 0.2f)),
                    ),
            )

        /** A plug-in with more to say than fits on two lines. */
        val WORDY =
            nl.mattix.andamp.core.model.EffectSpec(
                id = "com.example.wordy",
                name = "Wordy",
                description =
                    "A room, a sweep and a shelf, and the reasons you would want them in that order " +
                        "rather than any other, at a length nobody asked for but somebody wrote anyway.",
                params =
                    listOf(
                        nl.mattix.andamp.core.model
                            .ParamSpec("level", "Level", default = 0.5f),
                    ),
            )

        /** A plug-in with more named settings than fit on one line. */
        val MANY_PRESETS =
            nl.mattix.andamp.core.model.EffectSpec(
                id = "com.example.many",
                name = "Many",
                params =
                    listOf(
                        nl.mattix.andamp.core.model
                            .ParamSpec("level", "Level", default = 0.5f),
                    ),
                presets =
                    listOf("Flat", "Warm", "Club", "Headphones", "Late at night", "On the bus").map {
                        nl.mattix.andamp.core.model
                            .Preset(it, mapOf("level" to 0.3f))
                    },
            )

        /** Narrower than this and a preset chip's label has been squeezed. */
        val READABLE = 32.dp

        /** A plug-in with one control, which is a switch. */
        val TOGGLING =
            nl.mattix.andamp.core.model.EffectSpec(
                id = "com.example.toggling",
                name = "Toggling",
                params =
                    listOf(
                        nl.mattix.andamp.core.model
                            .ParamSpec("ebs", "Enhanced Bass", default = 0f, toggle = true),
                    ),
            )

        const val LONG_PRESS_MS = 700L
        const val STEPS = 24
        const val STEP_PX = 60f
    }

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun clearRack() {
        // DspOps remembers the rack; without this each test inherits the last one's
        app
            .getSharedPreferences("dsp", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private class EffectBackend(
        scope: CoroutineScope,
        val applied: MutableList<RackSettings> = mutableListOf(),
        /** A plug-in this backend found, for the parts of the page only a plug-in reaches. */
        private val extra: nl.mattix.andamp.core.model.EffectSpec? = null,
    ) : PlaybackBackend by MockBackend(FakeTracks.tracks, scope) {
        private val delegate = MockBackend(FakeTracks.tracks, scope)
        override val state get() = delegate.state
        override val capabilities: Capabilities get() = delegate.capabilities.copy(hasDsp = true)

        override val effects: List<nl.mattix.andamp.core.model.EffectSpec>
            get() = BuiltInEffects.all + listOfNotNull(extra)

        override fun setDsp(rack: RackSettings) {
            applied += rack
        }
    }

    private var picked = 0

    private fun show(backend: EffectBackend): DspOps {
        val facade = PlayerFacade(backend)
        val ops = DspOps(app, facade)
        val plugins = PluginOps(app, facade, ops, scope)
        compose.setContent {
            PreferencesPage(
                access = LibraryAccess.GRANTED,
                dsp = ops,
                plugins = plugins,
                onRequestAccess = {},
                onPickPlugin = { picked++ },
                onClose = {},
            )
        }
        return ops
    }

    /**
     * Finders on the unmerged tree: a clickable card header merges its
     * children's semantics, and the tags this suite addresses live inside it.
     */
    private fun tag(value: String) = compose.onNodeWithTag(value, useUnmergedTree = true)

    private fun allTags(value: String) = compose.onAllNodesWithTag(value, useUnmergedTree = true)

    /** The rack has a page of its own; a card cannot be reached from the root. */
    private fun openRack() = tag("prefs.open.dsp").performClick()

    /**
     * Through the switch on the card, not through the ops: switching on
     * also opens the card, and that behavior lives in the UI.
     */
    private fun switchOn(
        @Suppress("UNUSED_PARAMETER") ops: DspOps,
        vararg pluginIds: String,
    ) {
        pluginIds.forEach { tag("dsp.$it.on").performScrollTo().performClick() }
        compose.waitForIdle()
    }

    @Test
    fun `the player's switches live on the Player page, and back returns to the list`() {
        show(EffectBackend(scope))

        allTags("prefs.shade.switch").assertCountEquals(0)
        tag("prefs.open.player").performScrollTo().performClick()
        tag("prefs.shade.switch").performScrollTo().assertIsDisplayed()
        tag("prefs.tapassist.switch").performScrollTo().assertIsDisplayed()

        tag("prefs.back").performClick()
        tag("prefs.open.player").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the licenses page names every project that ships`() {
        show(EffectBackend(scope))

        // under About, one level down from the list
        tag("prefs.open.about").performScrollTo().performClick()
        tag("prefs.open.licences").performScrollTo().performClick()

        Notices.ALL.forEach { tag("licence.${it.name}").performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun `the page lists every bundled effect`() {
        show(EffectBackend(scope))
        openRack()

        // by tag, not by text: "Pan" is both an effect and one of its sliders
        BuiltInEffects.ids.forEach {
            // the rack is taller than a phone: scroll to each card in turn
            tag("dsp.$it.title").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `switching an effect on reaches the audio chain`() {
        val backend = EffectBackend(scope)
        val ops = show(backend)
        openRack()

        tag("dsp.${BuiltInEffects.REVERB}.on").performScrollTo().performClick()

        assertTrue("the switch turns the effect on in the rack", ops.rack[BuiltInEffects.REVERB]!!.enabled)
        assertEquals(true, backend.applied.last()[BuiltInEffects.REVERB]!!.enabled)
        tag("dsp.${BuiltInEffects.REVERB}.on").assertIsOn()
    }

    @Test
    fun `picking a modulation mode reaches the audio chain`() {
        val backend = EffectBackend(scope)
        val ops = show(backend)
        openRack()
        switchOn(ops, BuiltInEffects.MODULATION)

        tag("dsp.${BuiltInEffects.MODULATION}.choice.phaser").performScrollTo().performClick()

        val phaser = 2f
        assertEquals(phaser, ops.rack[BuiltInEffects.MODULATION]!!.params["mode", 0f], 0f)
        assertEquals(phaser, backend.applied.last()[BuiltInEffects.MODULATION]!!.params["mode", 0f], 0f)
    }

    @Test
    fun `dragging a slider reaches the audio chain`() {
        val backend = EffectBackend(scope)
        val ops = show(backend)
        openRack()
        switchOn(ops, BuiltInEffects.MODULATION)

        compose
            .onNodeWithTag("dsp.${BuiltInEffects.MODULATION}.knob.stereo")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }

        assertEquals(1f, ops.rack[BuiltInEffects.MODULATION]!!.params["stereo", 0f], 0f)
        assertEquals(1f, backend.applied.last()[BuiltInEffects.MODULATION]!!.params["stereo", 0f], 0f)
    }

    @Test
    fun `a control's help shows only after its mark is pressed`() {
        val ops = show(EffectBackend(scope))
        openRack()
        switchOn(ops, BuiltInEffects.KARAOKE)
        val width = BuiltInEffects.karaoke.params.first { it.id == "width" }

        compose.onAllNodesWithText(width.help).assertCountEquals(0)
        tag("dsp.${BuiltInEffects.KARAOKE}.help.width").performScrollTo().performClick()

        compose.onNodeWithText(width.help).assertIsDisplayed()
        // the popup floats away from its row, so it has to say what it explains
        tag("dsp.${BuiltInEffects.KARAOKE}.help.width.title").assertIsDisplayed()
        compose.onAllNodesWithText(width.name).assertCountEquals(2)
    }

    @Test
    fun `a control with nothing to add carries no mark`() {
        show(EffectBackend(scope))
        openRack()

        // a plug-in's control may have no help, and an empty mark would be a
        // dead touch target
        BuiltInEffects.all.forEach { spec ->
            spec.params.filter { it.help.isBlank() }.forEach {
                allTags("dsp.${spec.id}.help.${it.id}").assertCountEquals(0)
            }
        }
    }

    @Test
    fun `a finger landing just past a snap point lands on it`() {
        val backend = EffectBackend(scope)
        val ops = show(backend)
        openRack()
        switchOn(ops, BuiltInEffects.MODULATION)

        // just past center
        tag("dsp.${BuiltInEffects.MODULATION}.knob.stereo").performScrollTo().performTouchInput {
            down(Offset(width * 0.515f, centerY))
            up()
        }
        compose.waitForIdle()

        assertEquals(0.5f, ops.rack[BuiltInEffects.MODULATION]!!.params["stereo", 0f], 0f)
        assertEquals(0.5f, backend.applied.last()[BuiltInEffects.MODULATION]!!.params["stereo", 0f], 0f)
    }

    @Test
    fun `a value set without a finger is left where it was put`() {
        // a slider that snapped every value it was given could not be stepped
        // off a snap point with the arrow keys
        val ops = show(EffectBackend(scope))
        openRack()
        switchOn(ops, BuiltInEffects.MODULATION)

        compose
            .onNodeWithTag("dsp.${BuiltInEffects.MODULATION}.knob.stereo")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.515f) }

        assertEquals(0.515f, ops.rack[BuiltInEffects.MODULATION]!!.params["stereo", 0f], 1e-4f)
    }

    /** A thumb that lands on a handle while scrolling must not rearrange the rack. */
    @Test
    fun `a drag that was not held first moves nothing`() {
        val ops = show(EffectBackend(scope))
        openRack()
        val before = ops.rack.slots.map { it.pluginId }
        val first = before.first()

        tag("dsp.$first.drag").performScrollTo().performTouchInput {
            down(center)
            repeat(STEPS) {
                advanceEventTime(16)
                moveBy(Offset(0f, STEP_PX))
            }
            up()
        }
        compose.waitForIdle()

        assertEquals(before, ops.rack.slots.map { it.pluginId })
    }

    @Test
    fun `dragging a handle to the bottom moves an effect the whole way`() {
        val ops = show(EffectBackend(scope))
        openRack()
        val moved =
            ops.rack.slots
                .first()
                .pluginId

        tag("dsp.$moved.drag").performScrollTo().performTouchInput {
            down(center)
            // the press has to be held before the drag counts, so a scrolling
            // page is not dragged apart by a finger that meant to scroll it
            advanceEventTime(LONG_PRESS_MS)
            moveBy(Offset(0f, 1f))
            // in steps, as a finger moves: one long jump would report a single
            // delta and not show whether each pass is measured against the
            // current order
            repeat(STEPS) {
                advanceEventTime(16)
                moveBy(Offset(0f, STEP_PX))
            }
            up()
        }
        compose.waitForIdle()

        assertEquals(
            "a drag over several cards moves the effect to the end",
            ops.rack.slots.lastIndex,
            ops.rack.slots.indexOfFirst { it.pluginId == moved },
        )
    }

    @Test
    fun `moving an effect down reorders the signal`() {
        val backend = EffectBackend(scope)
        val ops = show(backend)
        openRack()

        tag("dsp.${BuiltInEffects.KARAOKE}.menu").performScrollTo().performClick()
        tag("dsp.${BuiltInEffects.KARAOKE}.down").performClick()

        assertEquals(
            BuiltInEffects.MODULATION,
            ops.rack.slots
                .first()
                .pluginId,
        )
        assertEquals(
            BuiltInEffects.MODULATION,
            backend.applied
                .last()
                .slots
                .first()
                .pluginId,
        )
    }

    @Test
    fun `a card follows its effect when the order changes`() {
        // cards are keyed by plug-in, not by position, so a card's switch
        // state moves with its effect
        val ops = show(EffectBackend(scope))
        openRack()
        tag("dsp.${BuiltInEffects.KARAOKE}.on").performScrollTo().performClick()

        tag("dsp.${BuiltInEffects.KARAOKE}.menu").performScrollTo().performClick()
        tag("dsp.${BuiltInEffects.KARAOKE}.down").performClick()

        assertEquals(
            BuiltInEffects.MODULATION,
            ops.rack.slots
                .first()
                .pluginId,
        )
        // the switch that was turned on is still the karaoke's, wherever it sits
        tag("dsp.${BuiltInEffects.KARAOKE}.on").performScrollTo().assertIsOn()
        tag("dsp.${BuiltInEffects.MODULATION}.on").performScrollTo().assertIsOff()
    }

    @Test
    fun `the ends of the rack have nowhere to go`() {
        show(EffectBackend(scope))
        openRack()

        tag("dsp.${BuiltInEffects.ids.first()}.menu").performScrollTo().performClick()
        tag("dsp.${BuiltInEffects.ids.first()}.up").assertIsNotEnabled()
        tag("dsp.${BuiltInEffects.ids.first()}.down").performClick()
        tag("dsp.${BuiltInEffects.ids.last()}.menu").performScrollTo().performClick()
        tag("dsp.${BuiltInEffects.ids.last()}.down").assertIsNotEnabled()
    }

    @Test
    fun `effects start switched off`() {
        show(EffectBackend(scope))
        openRack()

        BuiltInEffects.ids.forEach {
            tag("dsp.$it.on").performScrollTo().assertIsOff()
        }
    }

    @Test
    fun `the root shows the rack's row and summary, not its cards`() {
        val ops = show(EffectBackend(scope))

        tag("prefs.open.dsp").assertIsDisplayed()
        compose.onNodeWithText("${ops.rack.slots.size} effects · none on").assertIsDisplayed()
        allTags("dsp.${BuiltInEffects.REVERB}.title").assertCountEquals(0)
    }

    @Test
    fun `the rack page goes back to the root`() {
        show(EffectBackend(scope))
        openRack()
        tag("dsp.${BuiltInEffects.REVERB}.title").performScrollTo().assertIsDisplayed()

        tag("prefs.back").performClick()

        tag("prefs.open.dsp").assertIsDisplayed()
        // and back is a page back, not the way out of the preferences
        tag("prefs.close").assertIsDisplayed()
    }

    @Test
    fun `the root counts what the rack has switched on`() {
        show(EffectBackend(scope))
        openRack()
        tag("dsp.${BuiltInEffects.REVERB}.on").performScrollTo().performClick()

        tag("prefs.back").performClick()

        compose.onNodeWithText("${BuiltInEffects.ids.size} effects · 1 on").assertIsDisplayed()
    }

    /** The Preamp is the input stage: its own section above the effects, fixed first, nothing to drag. */
    @Test
    fun `the preamp sits under Input, above the effects, with no handle to move it`() {
        val preamp =
            nl.mattix.andamp.core.model.EffectSpec(
                id = BuiltInEffects.PREAMP,
                name = "Preamp",
                params =
                    listOf(
                        nl.mattix.andamp.core.model
                            .ParamSpec("gain", "Gain", min = -12f, max = 12f, default = 0f),
                    ),
            )
        show(EffectBackend(scope, extra = preamp))
        openRack()

        compose.onNodeWithText("Input · before all effects").assertIsDisplayed()
        tag("dsp.${BuiltInEffects.PREAMP}.title").assertIsDisplayed()
        allTags("dsp.${BuiltInEffects.PREAMP}.drag").assertCountEquals(0)
        val input = compose.onNodeWithText("Input · before all effects").getUnclippedBoundsInRoot().top
        val preampAt = tag("dsp.${BuiltInEffects.PREAMP}.title").getUnclippedBoundsInRoot().top
        // the top bar says "Effects" too; the section's line under its label is unique
        val effects =
            compose
                .onNodeWithText(
                    "Audio flows top to bottom — the order changes the sound.",
                ).getUnclippedBoundsInRoot()
                .top
        assertTrue("the preamp is between Input and Effects", preampAt > input && preampAt < effects)
    }

    @Test
    fun `the rack has a Manage plug-ins button`() {
        show(EffectBackend(scope))
        openRack()

        tag("prefs.plugins.manage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Manage plug-ins").assertIsDisplayed()
    }

    /** Where to find more: on the rack itself, and beside the list of what was added. */
    @Test
    fun `both the rack and the plug-ins page lead to more effects on the site`() {
        val opened = mutableListOf<String>()
        val handler =
            object : androidx.compose.ui.platform.UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
        val facade = PlayerFacade(EffectBackend(scope))
        val ops = DspOps(app, facade)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides handler) {
                PreferencesPage(
                    access = LibraryAccess.GRANTED,
                    dsp = ops,
                    plugins = PluginOps(app, facade, ops, scope),
                    onRequestAccess = {},
                    onPickPlugin = {},
                    onClose = {},
                )
            }
        }
        openRack()

        tag("prefs.plugins.more").performScrollTo().performClick()
        tag("prefs.plugins.manage").performScrollTo().performClick()
        tag("prefs.plugins.more").performScrollTo().performClick()

        assertEquals(List(2) { MORE_EFFECTS_URL }, opened)
    }

    @Test
    fun `the plug-ins page has an add button that opens the picker`() {
        show(EffectBackend(scope))
        openRack()

        tag("prefs.plugins.manage").performScrollTo().performClick()
        tag("prefs.plugins.add").performClick()

        assertEquals("the button opens the system picker", 1, picked)
    }

    @Test
    fun `a plug-in's own switch is a switch, and reaches the audio chain`() {
        // a toggle is a number to the graph and a switch to a listener
        val backend = EffectBackend(scope, extra = TOGGLING)
        val ops = show(backend)
        tag("prefs.open.dsp").performClick()
        switchOn(ops, TOGGLING.id)

        tag("dsp.${TOGGLING.id}.toggle.ebs").performScrollTo().performClick()

        assertEquals(1f, ops.rack[TOGGLING.id]!!.params["ebs", 0f], 0f)
        assertEquals(1f, backend.applied.last()[TOGGLING.id]!!.params["ebs", 0f], 0f)
        tag("dsp.${TOGGLING.id}.toggle.ebs").assertIsOn()
    }

    @Test
    fun `a plug-in that asked for groups gets them`() {
        val ops = show(EffectBackend(scope, extra = GROUPED))
        tag("prefs.open.dsp").performClick()
        switchOn(ops, GROUPED.id)

        // the group's own title, its switch in the header, and its children
        tag("dsp.${GROUPED.id}.group.Room").performScrollTo().assertIsDisplayed()
        tag("dsp.${GROUPED.id}.toggle.on").assertIsDisplayed()
        tag("dsp.${GROUPED.id}.knob.size").assertIsDisplayed()
        compose.onNodeWithText("a line of its own").assertIsDisplayed()
    }

    @Test
    fun `a gated group is disabled and stays on screen`() {
        val ops = show(EffectBackend(scope, extra = GROUPED))
        tag("prefs.open.dsp").performClick()
        switchOn(ops, GROUPED.id)
        tag("dsp.${GROUPED.id}.knob.size").performScrollTo().assertIsEnabled()

        tag("dsp.${GROUPED.id}.toggle.on").performClick()

        tag("dsp.${GROUPED.id}.knob.size").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `a layout may rename a control without renaming the parameter`() {
        val ops = show(EffectBackend(scope, extra = GROUPED))
        tag("prefs.open.dsp").performClick()
        switchOn(ops, GROUPED.id)

        // declared as "Size", drawn as what the layout called it
        compose.onNodeWithText("How big").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a preset sets what it names and leaves the rest alone`() {
        val ops = show(EffectBackend(scope, extra = GROUPED))
        tag("prefs.open.dsp").performClick()
        switchOn(ops, GROUPED.id)
        ops.setValue(GROUPED.id, "size", 0.9f)
        // switched off by hand: the preset says nothing about it, so it stays
        ops.setValue(GROUPED.id, "on", 0f)

        tag("dsp.${GROUPED.id}.preset.wide").performScrollTo().performClick()

        assertEquals("the preset sets the size it names", 0.2f, ops.rack[GROUPED.id]!!.params["size", -1f], 1e-6f)
        assertEquals("the preset leaves the switch it does not name", 0f, ops.rack[GROUPED.id]!!.params["on", -1f], 0f)
    }

    @Test
    fun `a plug-in may name as many settings as it likes`() {
        // every preset chip keeps a readable width, however many there are
        val ops = show(EffectBackend(scope, extra = MANY_PRESETS))
        tag("prefs.open.dsp").performClick()
        switchOn(ops, MANY_PRESETS.id)

        MANY_PRESETS.presets.forEach { preset ->
            val button = tag("dsp.${MANY_PRESETS.id}.preset.${preset.name.lowercase()}")
            button.performScrollTo().assertIsDisplayed()
            val width = button.getUnclippedBoundsInRoot().width
            assertTrue("${preset.name} keeps a readable width: $width", width > READABLE)
        }
    }

    @Test
    fun `with nothing installed no plug-in is marked missing`() {
        val ops = show(EffectBackend(scope))
        openRack()
        tag("prefs.plugins.manage").performScrollTo().performClick()

        // nothing installed, so nothing claims to be missing either
        allTags("plugin.com.example.gain.missing").assertCountEquals(0)
        assertTrue("the catalog lists the rack's effects", ops.catalogue.isNotEmpty())
    }

    @Test
    fun `two groups with two switches move on their own`() {
        // a group answers to its own switch and to no other
        val ops = show(EffectBackend(scope, extra = TWO_GATES))
        tag("prefs.open.dsp").performClick()
        switchOn(ops, TWO_GATES.id)

        tag("dsp.${TWO_GATES.id}.toggle.first").performScrollTo().performClick()

        assertEquals("the touched switch turns off", 0f, ops.rack[TWO_GATES.id]!!.params["first", -1f], 0f)
        assertEquals("the other switch stays on", 1f, ops.rack[TWO_GATES.id]!!.params["second", 1f], 0f)
        tag("dsp.${TWO_GATES.id}.knob.a").assertIsNotEnabled()
        tag("dsp.${TWO_GATES.id}.knob.b").assertIsEnabled()
    }

    @Test
    fun `a card can keep its settings and be put back to them`() {
        val ops = show(EffectBackend(scope))
        openRack()
        ops.setValue(BuiltInEffects.REVERB, "size", 0.9f)

        tag("dsp.${BuiltInEffects.REVERB}.menu").performScrollTo().performClick()
        tag("dsp.${BuiltInEffects.REVERB}.menu.keep").performClick()
        ops.setValue(BuiltInEffects.REVERB, "size", 0.1f)
        tag("dsp.${BuiltInEffects.REVERB}.menu").performClick()
        tag("dsp.${BuiltInEffects.REVERB}.menu.restore").performClick()

        assertEquals(0.9f, ops.rack[BuiltInEffects.REVERB]!!.params["size", -1f], 1e-4f)
    }

    @Test
    fun `a card can go back to what its effect declared`() {
        val ops = show(EffectBackend(scope))
        openRack()
        ops.setValue(BuiltInEffects.REVERB, "size", 0.9f)

        tag("dsp.${BuiltInEffects.REVERB}.menu").performScrollTo().performClick()
        tag("dsp.${BuiltInEffects.REVERB}.menu.reset").performClick()

        val declared =
            BuiltInEffects.reverb.params
                .first { it.id == "size" }
                .default
        assertEquals(declared, ops.rack[BuiltInEffects.REVERB]!!.params["size", -1f], 1e-4f)
    }

    @Test
    fun `a card opens with its switch, and can be opened without it`() {
        // power and visibility are separate: switching on opens the card, and
        // a header tap opens it without engaging the effect
        val ops = show(EffectBackend(scope))
        openRack()

        allTags("dsp.${BuiltInEffects.REVERB}.knob.size").assertCountEquals(0)

        tag("dsp.${BuiltInEffects.REVERB}.expand").performScrollTo().performClick()
        tag("dsp.${BuiltInEffects.REVERB}.knob.size").performScrollTo().assertIsDisplayed()
        assertEquals("opening the card leaves the effect off", false, ops.rack[BuiltInEffects.REVERB]!!.enabled)

        tag("dsp.${BuiltInEffects.REVERB}.expand").performClick()
        allTags("dsp.${BuiltInEffects.REVERB}.knob.size").assertCountEquals(0)

        switchOn(ops, BuiltInEffects.REVERB)
        tag("dsp.${BuiltInEffects.REVERB}.knob.size").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a long description can be read in full`() {
        // the full text lives in the opened card; the closed one carries only
        // its first sentence as a status line
        show(EffectBackend(scope, extra = WORDY))
        openRack()

        tag("dsp.${WORDY.id}.expand").performScrollTo().performClick()

        compose
            .onAllNodesWithText(WORDY.description, useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
    }
}
