// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import nl.mattix.andamp.state.AppViewModels
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.overlay.OverlayCoordinator
import nl.mattix.andamp.ui.WinampScreen
import nl.mattix.andamp.ui.online.SkinThumbnails
import nl.mattix.andamp.ui.overlay.PlayerOverlay
import nl.mattix.andamp.ui.prefs.overlaySettingsIntent

class MainActivity : ComponentActivity() {
    /** The process's one player, not this activity's: the floating overlay shows the same one. */
    private val player: WinampViewModel by lazy {
        ViewModelProvider(
            AppViewModels,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[WinampViewModel::class.java]
    }

    /** Shows, hides and finishes. Every site below writes a gate input and hands the gate here. */
    private val overlay by lazy {
        OverlayCoordinator(
            show = { showFloating() },
            hide = { PlayerOverlay.hide() },
            finish = { finish() },
            shown = { PlayerOverlay.showing },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Upright on a phone, where the player is a column as wide as the screen; any way round on
        // a tablet or a Chromebook, where playerScale fits the stack to the height. Set here
        // because the manifest can only lock every screen or none.
        requestedOrientation =
            if (resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_DP) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // museum thumbnails: memory for this session, disk for the next one
        SkinThumbnails.install(applicationContext)
        // With the floating player enabled, launching and tapping the media notification put the
        // floating one on screen and leave no activity behind. What needs an activity (a file
        // picker, Preferences, the museum) asks for one with SHOW_THE_APP.
        if (savedInstanceState == null && !intent.wantsTheApp() && player.overlayOps.gate.wanted) {
            player.overlayOps.recheck()
            if (player.overlayOps.gate.permitted) {
                // opening the player arms a minimized one again, as opening the app does
                player.overlayOps.enteredApp()
                player.overlayOps.leftApp()
                overlay.handOver(player.overlayOps.gate)
                if (PlayerOverlay.showing) return
            }
        }
        // only a fresh delivery: an activity keeps the intent it was started with, so a recreation
        // would navigate again and re-arm the visit
        if (savedInstanceState == null) goWhereAsked(intent)
        setContent {
            WinampScreen(
                player,
                // a screen opened from the widget hands the home screen back when it is done with.
                // The task goes to the back and is not finished, so the app is where the listener
                // left it next time
                onDone = { moveTaskToBack(true) },
                // switched on from the clutter bar's A: the floating player takes over at once, as
                // Winamp's Always On Top does. Switched off needs nothing here: this activity is
                // already where the player goes back to.
                onFloatingChanged = { on ->
                    if (on) {
                        player.overlayOps.leftApp()
                        overlay.handOver(player.overlayOps.gate)
                    }
                },
            )
        }
    }

    /**
     * An activity that is already running is told here, not in onCreate: the widget's menu brings
     * this one to the front, and singleTop routes its intent here.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        goWhereAsked(intent)
    }

    /**
     * A place named from outside, so the widget's menu can ask for Preferences without the listener
     * passing through the player first.
     */
    private fun goWhereAsked(intent: android.content.Intent) {
        val where = intent.getStringExtra(GO_TO) ?: return
        // spent on the way in, so a recreation inside this process cannot read
        // it a second time
        intent.removeExtra(GO_TO)
        player.state.arrivalDestination = where
        player.state.arrivedAt = where
        intent.getStringExtra(GO_TO_PAGE)?.let {
            intent.removeExtra(GO_TO_PAGE)
            player.state.arrivalPage = it
        }
    }

    private fun android.content.Intent.wantsTheApp() = getBooleanExtra(SHOW_THE_APP, false)

    override fun onStart() {
        super.onStart()
        // in front again: the floating one steps aside, and a window closed with
        // its X is armed for the next time
        player.overlayOps.enteredApp()
        overlay.settle(player.overlayOps.gate)
    }

    override fun onStop() {
        super.onStop()
        // leaving the app ends a visit that came from outside it, so a later Done inside the app
        // does not send the task to the background
        player.state.leftTheApp()
        // the view model outlives every activity, so its state is flushed here
        player.persistence.flush()
        player.overlayOps.leftApp()
        overlay.settle(player.overlayOps.gate)
    }

    /**
     * The floating player, with the three things it cannot do for itself. X stops the music and
     * closes the player; minimize puts the window away and leaves it playing, and the media
     * notification is the way back.
     */
    private fun showFloating() {
        // without this the process is cached once this activity is gone, and a cached process is
        // frozen; see PlaybackBackend.keepAlive
        player.keepPlayingWithoutAWindow()
        PlayerOverlay.show(
            this,
            player,
            onOpenApp = { comeForward() },
            onOpenAppAt = { place -> comeForward(place) },
            onClose = {
                player.exit() // Winamp's X: the music stops and nothing stays running
                player.overlayOps.dismiss()
                overlay.settle(player.overlayOps.gate)
            },
            onMinimize = {
                player.overlayOps.dismiss()
                overlay.settle(player.overlayOps.gate)
            },
        )
    }

    /**
     * What the floating player does with anything needing an activity: opens this one, aimed at
     * [destination] when the menu named a place.
     *
     * The activity is started while the overlay is still up: an app with no visible activity may
     * start one only while it has a window on screen.
     */
    private fun comeForward(destination: String? = null) {
        // the system's own screen, not one of ours: it needs no activity of
        // this app behind it, only that one is started while the overlay shows
        if (destination == PlayerOverlay.OVERLAY_SETTINGS) {
            startActivity(overlaySettingsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            overlay.stepAside()
            return
        }
        destination?.let { player.state.arrivalDestination = it }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(SHOW_THE_APP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        overlay.stepAside()
    }

    internal companion object {
        /**
         * Set when the activity itself is asked for, for the things only an activity can do.
         * Without it, launching with the floating player enabled shows the floating one.
         */
        const val SHOW_THE_APP = "nl.mattix.andamp.show_the_app"

        /** The screen to land on. */
        const val GO_TO = "nl.mattix.andamp.go_to"

        /** The page of that screen, where it has pages. */
        const val GO_TO_PAGE = "nl.mattix.andamp.go_to_page"

        /** Android's line between a phone and a tablet: 600dp on the shorter side. */
        const val LARGE_SCREEN_DP = 600
    }
}
