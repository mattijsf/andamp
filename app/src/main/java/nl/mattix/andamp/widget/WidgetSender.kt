// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import nl.mattix.andamp.R
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.state.CurrentSkin
import nl.mattix.andamp.state.VisMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Turns the snapshot into RemoteViews and decides how little of them to send. The provider
 * receives; this sends. The decisions are [chromeOf] and [needsWholeWindow], which WidgetDiffTest
 * covers.
 */
internal object WidgetSender {
    /**
     * One thread for everything that renders or sends, so updates cannot overtake each other.
     * Nothing on it may sleep; a press's flash timing runs on [WidgetFlash]'s own clock.
     */
    private val io = Executors.newSingleThreadExecutor()

    /** What each widget was last sent, so a tick can send only the difference. */
    private data class Sent(
        val chrome: String,
        val layers: Map<WidgetLayer, String>,
        val visRunning: Boolean,
        val visGeneration: Int,
        val patches: Int,
    )

    /**
     * How many patches may follow one window before it is drawn whole again. A patch assumes the
     * launcher still shows what was last sent; the periodic whole send bounds how long a wrong
     * pixel can stay.
     */
    private const val PATCHES_PER_WINDOW = 60

    private val sent = ConcurrentHashMap<Int, Sent>()

    /**
     * Everything about the picture that is not one of the moving parts.
     *
     * Comparing this decides between sending the window and sending patches of it, so it names
     * every reason the rest could look different and none that only a layer shows. The position,
     * writtenAt and the bitrate are left out, since each changes on most ticks; WidgetDiffTest
     * checks all three.
     */
    fun chromeOf(
        snapshot: WidgetSnapshot,
        layout: WidgetLayout,
        pressedWidget: String?,
        settings: WidgetSettings,
        skinId: String,
    ) = "${snapshot.copy(positionSec = 0, writtenAt = 0, bitrateKbps = null)}|$layout|$pressedWidget|$settings|$skinId"

    /**
     * Whether a patch will not do and the window itself has to go: no layers to patch (below API
     * 31, or windowshade), a broadcast asking from scratch, nothing sent yet, a change outside the
     * layers, or [PATCHES_PER_WINDOW] patches in a row.
     */
    internal fun needsWholeWindow(
        layered: Boolean,
        whole: Boolean,
        sentBefore: Boolean,
        chromeSame: Boolean,
        patches: Int,
    ) = !layered || whole || !sentBefore || !chromeSame || patches >= PATCHES_PER_WINDOW

    /** Whether any widget is placed. */
    fun hasInstances(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return manager.getAppWidgetIds(ComponentName(context, MainWindowWidget::class.java)).isNotEmpty()
    }

    /** Redraws every placed widget. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, MainWindowWidget::class.java))
        ids.forEach { render(context, manager, it, whole = false) }
    }

    /**
     * Drops the memo of removed widgets. Queued behind the renders: a render for the same id may
     * already be waiting and ends by writing its memo back, which would undo a forget run before
     * it.
     */
    fun forget(ids: IntArray) {
        io.execute { ids.forEach { sent.remove(it) } }
    }

    fun forgetAll() {
        io.execute { sent.clear() }
    }

    /**
     * Runs [work] after everything queued so far has been sent. The provider holds its broadcast
     * open until then, because a process cold-started by a press may be killed once onReceive
     * returns.
     */
    fun afterQueue(work: Runnable) {
        io.execute(work)
    }

    fun render(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        whole: Boolean,
    ) {
        // read here rather than inside io: the box decides the scale, and the
        // scale is part of the layout every patch is placed against
        val size = WidgetSettings.read(context).size
        val (width, height) = WidgetBox.of(context, manager.getAppWidgetOptions(id))
        // parsing a skin is a zip and many bitmaps: too much for the main thread a broadcast
        // arrives on
        io.execute {
            // the skin and the key it was cached under, from one read of what
            // is worn: a source's skin going on between two reads would pair
            // this picture with the next one's key
            val (skinKey, skin) = CurrentSkin.keyed(context) ?: return@execute
            val layout = WidgetLayout.choose(width, height, fill = size == WidgetSize.FILL)
            // a snapshot that says it is playing may have been written by a process that has since
            // gone
            val snapshot = WidgetSnapshot.read(context).asKnown(SystemClock.elapsedRealtime(), WidgetControl.attached)
            val state = snapshot.toState().apply { pressedWidget = WidgetFlash.pressed }
            // the settings decide what is placed, not just what is drawn, so a
            // change of them has to send the window rather than a patch of it
            val settings = WidgetSettings.read(context)
            // the skin is part of the chrome, so a new skin repaints the whole window and not only
            // the patches. The key and not the id, so a wallpaper changing under a live skin is a
            // whole window too
            val chrome = chromeOf(snapshot, layout, state.pressedWidget, settings, skinKey)
            val layers = WidgetLayer.entries.associateWith { it.signature(state) }
            // one read, so the frames and their generation come from the same batch
            val visBatch = WidgetVisFrames.batch
            val captured = visBatch.frames
            val visGeneration = visBatch.generation
            val visRunning =
                settings.mode != VisMode.Off &&
                    snapshot.transport == Transport.Playing &&
                    !layout.shaded &&
                    captured.isNotEmpty()
            val last = sent[id]

            // Below API 31 a layer cannot be positioned, so the whole window goes every time. The
            // flipper appearing or disappearing is also a whole send.
            //
            // Windowshade has no layers either: Placement.layers hides them, and the shade's own
            // clock and thumb are painted into the base picture by drawMainShade.
            val layered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !layout.shaded
            val wholeWindow =
                needsWholeWindow(
                    layered = layered,
                    whole = whole,
                    sentBefore = last != null,
                    chromeSame = last?.chrome == chrome,
                    patches = last?.patches ?: 0,
                )
            if (wholeWindow || last?.visRunning != visRunning) {
                val views = RemoteViews(context.packageName, R.layout.widget_main)
                views.setImageViewBitmap(R.id.widget_player, WidgetRender.bitmap(skin, layout, state))
                // the whole picture opens the app unless the listener asked for only the logo to.
                // Null is set explicitly: a view that carried the intent before has to be told it
                // no longer does
                views.setOnClickPendingIntent(
                    R.id.widget_player,
                    if (settings.openOnLogo) null else WidgetIntents.openApp(context),
                )
                // the pixels cannot be read aloud; this is the widget in words
                views.setContentDescription(R.id.widget_player, snapshot.spoken())
                val placement = Placement(context, views, skin, layout)
                placement.base()
                placement.buttons(snapshot)
                placement.logo(settings)
                placement.menu()
                placement.visualizer(state, settings.mode, visRunning, captured)
                placement.sliders(snapshot, settings)
                if (layered) placement.layers(state)
                manager.updateAppWidget(id, views)
                sent[id] = Sent(chrome, layers, visRunning, visGeneration, patches = 0)
                return@execute
            }

            // Only what moved: a tick is a few small layers, a burst is eight small visualizer
            // frames. `last` is non-null here: with nothing sent, needsWholeWindow is true.
            val moved = layers.filter { (part, now) -> last.layers[part] != now }
            val visMoved = visRunning && last.visGeneration != visGeneration
            if (moved.isEmpty() && !visMoved) return@execute
            val views = RemoteViews(context.packageName, R.layout.widget_main)
            moved.keys.forEach { part ->
                views.setImageViewBitmap(idOf(part), WidgetRender.layer(skin, layout, state, part))
            }
            if (visMoved) {
                // the flipper keeps its place from the last whole send; only
                // its pictures change hands here
                WidgetRender
                    .visualizerFrames(skin, layout, state, captured, settings.mode)
                    .forEachIndexed { at, frame -> VIS_SLOTS.getOrNull(at)?.let { views.setImageViewBitmap(it, frame) } }
            }
            manager.partiallyUpdateAppWidget(id, views)
            sent[id] = Sent(chrome, layers, visRunning, visGeneration, patches = last.patches + 1)
        }
    }

    /** One whole-window send's worth of placing: every view moved onto the picture. */
    private class Placement(
        private val context: Context,
        private val views: RemoteViews,
        private val skin: nl.mattix.andamp.skin.Skin,
        private val layout: WidgetLayout,
    ) {
        // the stage is the art's size and the framework centers it, so a patch's margins are the
        // art's own coordinates

        /**
         * Pins the window's own picture where the patches expect it.
         *
         * Left to `centerInside`, the launcher centers the bitmap inside the real view, whose size
         * includes any padding the host adds and can differ from the reported box. The patches are
         * placed from the box size, so they would sit beside what they replace. Placed explicitly,
         * base and patches share one origin. Below API 31 nothing can be placed and nothing is
         * patched, so centerInside stays.
         */
        fun base() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            // Below API 31 the stage wraps its picture. From 31 both the stage and the picture are
            // pinned in pixels: a wrap_content ImageView measures the drawable's intrinsic size,
            // which for a bitmap from another process is scaled by the host's density and would not
            // match the coordinates the patches are placed at.
            views.setViewLayoutWidth(R.id.widget_stage, layout.widthPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutHeight(R.id.widget_stage, layout.heightPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            place(R.id.widget_player, 0, 0, layout.widthPx, layout.heightPx)
        }

        private fun place(
            viewId: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
        ) {
            views.setViewLayoutWidth(viewId, width.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutHeight(viewId, height.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutMargin(viewId, RemoteViews.MARGIN_START, left.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutMargin(viewId, RemoteViews.MARGIN_TOP, top.toFloat(), TypedValue.COMPLEX_UNIT_PX)
        }

        /**
         * Moves the transport's touch regions onto the drawn buttons. Setting a view's size and
         * margin from RemoteViews needs API 31; below it the regions stay collapsed and the whole
         * picture opens the player.
         */
        fun buttons(snapshot: WidgetSnapshot) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            WidgetButtons.boxes(layout).forEachIndexed { at, box ->
                val id = BUTTON_SLOTS.getOrNull(at) ?: return@forEachIndexed
                place(id, box.left, box.top, box.width, box.height)
                // a flag's press carries the value to set, worked out from the snapshot being drawn
                val target =
                    when (box.button) {
                        WidgetButton.SHUFFLE -> !snapshot.shuffle
                        WidgetButton.REPEAT -> !snapshot.repeat
                        else -> false
                    }
                views.setOnClickPendingIntent(id, WidgetIntents.press(context, box.button, target))
            }
        }

        /**
         * Hands the launcher the visualizer frames to cycle, or hides the flipper. Only while
         * something is playing: a stopped player draws a dark analyzer.
         */
        fun visualizer(
            state: nl.mattix.andamp.state.WinampState,
            mode: VisMode,
            running: Boolean,
            captured: List<VisFrame>,
        ) {
            if (!running || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                views.setViewVisibility(R.id.widget_vis, View.GONE)
                return
            }
            WidgetRender
                .visualizerFrames(skin, layout, state, captured, mode)
                .forEachIndexed { at, frame -> VIS_SLOTS.getOrNull(at)?.let { views.setImageViewBitmap(it, frame) } }
            val at = WidgetRender.visualizerRect(layout)
            place(R.id.widget_vis, at.x, at.y, at.w, at.h)
            views.setViewVisibility(R.id.widget_vis, View.VISIBLE)
        }

        /**
         * The player's own top-left button, which opens the menu ([WidgetMenuActivity]). The target
         * is larger than the nine-pixel button: it covers the title bar's left end.
         */
        fun menu() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val at = layout.rect(MENU_X, MENU_Y, MENU_W, MENU_H)
            place(R.id.widget_menu, at.x, at.y, at.w, at.h)
            views.setOnClickPendingIntent(R.id.widget_menu, WidgetIntents.menu(context))
        }

        /**
         * The Winamp logo, as the way into the app when the picture itself is not one. The target
         * is larger than the art it covers.
         */
        fun logo(settings: WidgetSettings) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            if (!settings.openOnLogo || layout.shaded) {
                views.setViewLayoutWidth(R.id.widget_logo, 0f, TypedValue.COMPLEX_UNIT_PX)
                return
            }
            val at = layout.rect(LOGO_X, LOGO_Y, LOGO_W, LOGO_H)
            place(R.id.widget_logo, at.x, at.y, at.w, at.h)
            views.setOnClickPendingIntent(R.id.widget_logo, WidgetIntents.openApp(context))
        }

        /** Lays the slider targets over the tracks they belong to. */
        fun sliders(
            snapshot: WidgetSnapshot,
            settings: WidgetSettings,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            var seekAt = 0
            var volumeAt = 0
            // every target is collapsed first, so a stream's bar or a switched-off volume keeps
            // none from before
            SEEK_SLOTS.forEach { views.setViewLayoutWidth(it, 0f, TypedValue.COMPLEX_UNIT_PX) }
            VOLUME_SLOTS.forEach { views.setViewLayoutWidth(it, 0f, TypedValue.COMPLEX_UNIT_PX) }
            WidgetSliders.steps(layout, snapshot.seekable, settings.volumeControl).forEach { step ->
                val id =
                    when (step.slider) {
                        WidgetSlider.SEEK -> SEEK_SLOTS.getOrNull(seekAt++)
                        WidgetSlider.VOLUME -> VOLUME_SLOTS.getOrNull(volumeAt++)
                    } ?: return@forEach
                place(id, step.left, step.top, step.width, step.height)
                views.setOnClickPendingIntent(id, WidgetIntents.slide(context, step))
            }
        }

        /** Lays each moving part over the place in the window it belongs to. */
        fun layers(state: nl.mattix.andamp.state.WinampState) {
            WidgetLayer.entries.forEach { part ->
                val id = idOf(part)
                // the shade draws none of these
                if (layout.shaded) {
                    views.setViewVisibility(id, View.GONE)
                    return@forEach
                }
                val at = layout.rect(part.left, part.top, part.width, part.height)
                views.setImageViewBitmap(id, WidgetRender.layer(skin, layout, state, part))
                place(id, at.x, at.y, at.w, at.h)
                views.setViewVisibility(id, View.VISIBLE)
            }
        }
    }

    private fun idOf(part: WidgetLayer) =
        when (part) {
            WidgetLayer.CLOCK -> R.id.widget_layer_clock
            WidgetLayer.MARQUEE -> R.id.widget_layer_marquee
            WidgetLayer.POSBAR -> R.id.widget_layer_posbar
            WidgetLayer.READOUT -> R.id.widget_layer_readout
        }

    /** One view per control; the layout declares them, and WidgetLayerTest checks the counts. */
    internal val BUTTON_SLOTS =
        intArrayOf(
            R.id.widget_btn_0,
            R.id.widget_btn_1,
            R.id.widget_btn_2,
            R.id.widget_btn_3,
            R.id.widget_btn_4,
            R.id.widget_btn_5,
            R.id.widget_btn_6,
        )

    internal val SEEK_SLOTS =
        intArrayOf(
            R.id.widget_seek_0,
            R.id.widget_seek_1,
            R.id.widget_seek_2,
            R.id.widget_seek_3,
            R.id.widget_seek_4,
            R.id.widget_seek_5,
            R.id.widget_seek_6,
            R.id.widget_seek_7,
            R.id.widget_seek_8,
            R.id.widget_seek_9,
            R.id.widget_seek_10,
            R.id.widget_seek_11,
        )

    /**
     * The options button's box (MainWindow draws it at 6,3, 9 by 9), grown to the title bar's left
     * end.
     */
    private const val MENU_X = 0
    private const val MENU_Y = 0
    private const val MENU_W = 22
    private const val MENU_H = 16

    /**
     * The logo's box: the player's About button (MainWindow's ABOUT_X and friends), grown into the
     * empty corner beside it.
     */
    private const val LOGO_X = 246
    private const val LOGO_Y = 84
    private const val LOGO_W = 29
    private const val LOGO_H = 24

    internal val VOLUME_SLOTS =
        intArrayOf(
            R.id.widget_vol_0,
            R.id.widget_vol_1,
            R.id.widget_vol_2,
            R.id.widget_vol_3,
            R.id.widget_vol_4,
            R.id.widget_vol_5,
            R.id.widget_vol_6,
            R.id.widget_vol_7,
            R.id.widget_vol_8,
            R.id.widget_vol_9,
        )

    internal val VIS_SLOTS =
        intArrayOf(
            R.id.widget_vis_0,
            R.id.widget_vis_1,
            R.id.widget_vis_2,
            R.id.widget_vis_3,
            R.id.widget_vis_4,
            R.id.widget_vis_5,
            R.id.widget_vis_6,
            R.id.widget_vis_7,
        )
}
