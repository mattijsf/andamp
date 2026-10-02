// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

/** Loads the native libraries and answers what was linked. */
object ProjectM {
    init {
        System.loadLibrary("andamp_projectm")
    }

    /** The linked libprojectM version, e.g. `4.1.7`. */
    fun version(): String = nativeVersion()

    /** Touching the object runs [init]; this says so at the call site. */
    fun ensureLoaded() = Unit

    private external fun nativeVersion(): String
}
