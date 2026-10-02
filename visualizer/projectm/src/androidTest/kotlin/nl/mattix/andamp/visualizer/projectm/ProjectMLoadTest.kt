// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The native path is not JVM-testable, so on-device verification is its gate
 * (see the testing note in docs/projectm-integration.md). This is the smallest
 * form of it: the packaged libraries dlopen and the linked version is the pinned one.
 */
@RunWith(AndroidJUnit4::class)
class ProjectMLoadTest {
    @Test
    fun the_linked_libprojectM_is_the_pinned_submodule_revision() {
        assertEquals(PINNED_VERSION, ProjectM.version())
    }

    private companion object {
        // third_party/projectm is pinned to tag v4.1.7
        const val PINNED_VERSION = "4.1.7"
    }
}
