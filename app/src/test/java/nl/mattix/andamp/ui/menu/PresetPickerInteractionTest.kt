// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import nl.mattix.andamp.state.PresetEntry
import nl.mattix.andamp.state.PresetPicker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preset list dialog: several rows for a delete, one for a load, and a
 * confirm button that is enabled once something is picked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PresetPickerInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private var confirmed: List<String>? = null
    private var dismissed = false

    private fun show(
        entries: Int,
        multiSelect: Boolean = true,
        label: String = "Delete",
    ) {
        val picker =
            PresetPicker(
                title = "Delete auto-load presets",
                entries = (1..entries).map { PresetEntry("k$it", "Song $it", "Artist $it") },
                confirmLabel = label,
                multiSelect = multiSelect,
                destructive = true,
                emptyMessage = "No song has a preset of its own yet",
            ) { confirmed = it }
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PresetPickerSheet(picker) { dismissed = true }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `nothing picked, nothing to press`() {
        show(entries = 3)

        compose.onNodeWithTag("$PRESET_PICKER_TAG.confirm").assertIsNotEnabled()
    }

    @Test
    fun `a delete takes as many as were picked`() {
        show(entries = 5)

        compose.onNodeWithTag("$PRESET_PICKER_TAG.row.k2").performClick()
        compose.onNodeWithTag("$PRESET_PICKER_TAG.row.k4").performClick()
        compose.onNodeWithText("Delete (2)").performClick()

        assertEquals(listOf("k2", "k4"), confirmed)
    }

    @Test
    fun `picking a row again puts it back`() {
        show(entries = 5)

        compose.onNodeWithTag("$PRESET_PICKER_TAG.row.k2").performClick()
        compose.onNodeWithTag("$PRESET_PICKER_TAG.row.k2").performClick()

        compose.onNodeWithTag("$PRESET_PICKER_TAG.confirm").assertIsNotEnabled()
    }

    @Test
    fun `a load takes one, and the second pick replaces the first`() {
        show(entries = 5, multiSelect = false, label = "Load")

        compose.onNodeWithTag("$PRESET_PICKER_TAG.row.k1").performClick()
        compose.onNodeWithTag("$PRESET_PICKER_TAG.row.k3").performClick()
        compose.onNodeWithText("Load").performClick()

        assertEquals(listOf("k3"), confirmed)
    }

    @Test
    fun `a long list gets a search field, and it filters`() {
        show(entries = 30)

        compose.onNodeWithTag("$PRESET_PICKER_TAG.search").performTextInput("song 17")
        compose.waitForIdle()

        compose.onNodeWithTag("$PRESET_PICKER_TAG.row.k17").performClick()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(listOf("k17"), confirmed)
    }

    @Test
    fun `an empty shelf shows only its own message`() {
        show(entries = 0)

        compose.onNodeWithText("No song has a preset of its own yet").assertExists()
        compose.onNodeWithText("Nothing saved").assertDoesNotExist()
    }

    @Test
    fun `the extra button takes the picked row and leaves the dialog open`() {
        var extraPicked: List<String>? = null
        val picker =
            PresetPicker(
                title = "Open playlist",
                entries = (1..3).map { PresetEntry("k$it", "List $it") },
                confirmLabel = "Open",
                extra = PresetPicker.Extra("Delete", destructive = true) { extraPicked = it },
            ) { confirmed = it }
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PresetPickerSheet(picker) { dismissed = true }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("$PRESET_PICKER_TAG.extra").assertIsNotEnabled()

        compose.onNodeWithTag("$PRESET_PICKER_TAG.row.k2").performClick()
        compose.onNodeWithTag("$PRESET_PICKER_TAG.extra").performClick()

        assertEquals(listOf("k2"), extraPicked)
        assertEquals(null, confirmed)
        assertEquals(false, dismissed)
    }

    @Test
    fun `a picker without an extra has only its two buttons`() {
        show(entries = 3)

        compose.onNodeWithTag("$PRESET_PICKER_TAG.extra").assertDoesNotExist()
    }

    @Test
    fun `cancel leaves everything alone`() {
        show(entries = 3)

        compose.onNodeWithTag("$PRESET_PICKER_TAG.row.k1").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(null, confirmed)
        assertEquals(true, dismissed)
    }
}
