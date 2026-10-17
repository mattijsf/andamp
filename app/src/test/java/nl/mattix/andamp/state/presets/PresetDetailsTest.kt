// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetDetailsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val milk =
        """
        [preset00]
        fRating=4.000
        fGammaAdj=1.900
        fDecay=0.980
        nWaveMode=2
        MILKDROP_PRESET_VERSION=201
        PSVERSION=3
        PSVERSION_WARP=2
        PSVERSION_COMP=3
        wavecode_0_enabled=1
        wavecode_1_enabled=0
        shapecode_0_enabled=1
        shapecode_3_enabled=1
        per_frame_1=q1 = bass;
        per_frame_2=q12 = time*0.1;
        per_pixel_1=zoom = 1 + q1*0.01;
        warp_1=`shader_body {
        warp_2=`  ret = tex2D(sampler_main, uv).xyz + tex2D(sampler_noise_lq, uv).xyz;
        warp_3=`}
        comp_1=`shader_body {
        comp_2=`  ret = tex2D(sampler_fw_clouds, uv).xyz * q64 + tex2D(sampler_rand03, uv).xyz + tex2D(sampler_gone, uv).xyz;
        comp_3=`  ret += GetBlur1(uv) * q12;
        comp_4=`}
        """.trimIndent().lines()

    @Test
    fun `a milk file is read for its page`() {
        val textures = temp.newFolder("textures").apply { File(this, "Clouds.jpg").writeText("x") }

        val read = PresetDetails.readMilk(milk, listOf(textures))

        assertEquals(201, read.version)
        assertEquals(2, read.warpModel)
        assertEquals(3, read.compModel)
        assertEquals(1, read.customWaves)
        assertEquals(2, read.customShapes)
        assertEquals(3, read.warpLines)
        assertEquals(4, read.compLines)
        assertEquals(2, read.perFrameLines)
        assertEquals(1, read.perPixelLines)
        assertEquals(listOf(1, 12, 64), read.qVariables)
        assertEquals(
            listOf(
                PresetDetails.Texture("clouds", true),
                PresetDetails.Texture("rand03", null),
                PresetDetails.Texture("gone", false),
            ),
            read.textures,
        )
        assertEquals(listOf("fDecay" to "0.980", "fGammaAdj" to "1.900", "nWaveMode" to "2", "fRating" to "4.000"), read.settings)
    }

    @Test
    fun `a preset is placed in its pack, with its author`() {
        val dir = temp.newFolder("Cream")
        val file = File(dir, "Dancer/Jc - Crystal Shards.milk").apply { parentFile?.mkdirs() }
        file.writeText(milk.joinToString("\n"))
        val pack = InstalledPack(name = "Cream", dir = dir, presetCount = 1, textureDirs = emptyList())

        val details = PresetDetails.read(file.absolutePath, listOf(pack))

        assertEquals("Jc - Crystal Shards", details.name)
        assertEquals("Jc", details.author)
        assertEquals("Cream", details.pack)
        assertEquals("Dancer", details.folder)
        assertEquals(PresetFingerprint.of(file), details.fingerprint)
        assertNull("a milk file has no AVS outline", details.avs)
    }
}
