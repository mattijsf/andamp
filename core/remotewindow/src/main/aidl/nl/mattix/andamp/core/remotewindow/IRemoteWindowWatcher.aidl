// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.remotewindow;

/**
 * The caller of IRemoteWindow, told which of the player's windows is up on its surface.
 * The caller makes this and hands it to IRemoteWindow.watch.
 */
oneway interface IRemoteWindowWatcher {
    /** Whether the main window is up: false while the equalizer or the list is. */
    void mainUp(boolean up);
}
