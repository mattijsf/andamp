// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.remotewindow;

import android.view.Surface;

/**
 * The player's main window, shown on a surface another app lends it.
 *
 * The player implements this and draws; the caller owns the surface and reports
 * what happens on it. Every call returns at once, and calls arrive in the order
 * they were made.
 */
oneway interface IRemoteWindow {
    /**
     * Shows the main window on a surface of this size, in pixels, and density.
     * A second call moves the window to the new surface.
     */
    void show(in Surface surface, int width, int height, int densityDpi);

    /**
     * The part of the surface the caller leaves uncovered, in pixels. The window
     * is drawn inside it.
     */
    void keepTo(int left, int top, int right, int bottom);

    /** A tap on the surface, in its pixels. */
    void tap(float x, float y);

    /** Takes the window off the surface, which the caller is about to lose. */
    void hide();

    /**
     * A finger dragging across the surface: how far it has moved since the last
     * report, in pixels. Right and down are positive.
     */
    void drag(float dx, float dy);

    /**
     * A finger that left the surface while moving: its speed at that moment, in
     * pixels a second. Right and down are positive.
     */
    void fling(float vx, float vy);
}
