// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import nl.mattix.andamp.modernskin.maki.MakiFormatException
import nl.mattix.andamp.modernskin.maki.MakiHost
import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.MakiParser
import nl.mattix.andamp.modernskin.maki.Script
import nl.mattix.andamp.modernskin.maki.StdApi
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.xml.XmlNode
import nl.mattix.andamp.modernskin.xml.XmlParser

/**
 * A loaded modern skin: its resources, its containers, its scripts, and the glue between the player
 * and the objects. Everything runs on one thread; the app calls [tick] regularly and [draw] when
 * something changed.
 */
@Suppress("TooManyFunctions", "LargeClass") // the one host every object and script of a skin calls into
class SkinRuntime(
    val files: SkinFiles,
    val host: SkinHost,
    val player: Player,
) : MakiHost {
    private val bitmaps = HashMap<String, BitmapDef>()
    private val bitmapFonts = HashMap<String, BitmapFontDef>()
    private val fonts = HashMap<String, FontDef>()
    private val colors = HashMap<String, Rgb>()
    private val colorAliases = HashMap<String, String>()
    private val aliases = HashMap<String, String>()
    private val groupDefs = HashMap<String, XmlNode>()

    /** A groupdef that redefines an id, to the one it replaced: its base when it names none. */
    private val earlierDefs = HashMap<XmlNode, XmlNode>()
    private val xuiTags = HashMap<String, String>()
    private val programs = HashMap<String, nl.mattix.andamp.modernskin.maki.MakiProgram?>()
    val containers = ArrayList<Container>()
    val scripts = ArrayList<Script>()
    private val systems = ArrayList<SystemObject>()
    val timers = ArrayList<Timer>()
    val animations = ArrayList<AnimatedLayer>()

    /** Objects on their way to a target set with `setTarget*` and `gotoTarget`. */
    val moving = LinkedHashSet<GuiObject>()
    val privateInts = HashMap<String, Int>()
    val privateStrings = HashMap<String, String>()

    /**
     * Keeps a script's private setting, within bounds: at most [MAX_SETTINGS] names per kind and
     * strings of [MAX_SETTING_LENGTH] characters, so a skin cannot fill memory or storage with them.
     */
    fun <T> storePrivate(
        map: HashMap<String, T>,
        key: String,
        value: T,
    ) {
        val k = key.lowercase()
        if (k.length > MAX_SETTING_LENGTH ||
            (value is String && value.length > MAX_SETTING_LENGTH)
        ) {
            return host.log("setting $k too long, not kept")
        }
        if (k !in map && map.size >= MAX_SETTINGS) return host.log("too many settings, $k not kept")
        if (map.put(k, value) != value) settingsChanged()
    }

    /**
     * Counts changes to what a host keeps of the skin between runs: the scripts' private and public
     * values, the config attributes ([storedSettings]) and the color theme. Writing a value that is
     * already there changes nothing.
     */
    var settingsVersion = 0
        private set

    internal fun settingsChanged() {
        settingsVersion++
    }

    private val configFlags = HashMap<String, Boolean>()

    /** What getSkinName() answers: the skin's folder name, set by the host before [load]. */
    var skinName = ""

    /**
     * Whether Winamp's Media Library lives inside the skin (`@HAVE_LIBRARY@`). Winamp has it; Andamp's
     * library is a screen of its own, so the app says no and skins such as Bento show their
     * playlist tab instead of an empty library tab. Set before [load].
     */
    var haveLibrary = true

    /** The screen a skin may place windows on before the host sets [viewSize]: a Full HD portrait phone. */
    var viewportW = 1080
    var viewportH = 1920

    /** The screen as scripts ask for it (`getViewportWidth`): the phone's view in layout pixels once known. */
    val viewport: Pair<Int, Int> get() = viewSize ?: (viewportW to viewportH)

    /**
     * Called for a button action the app answers, as Winamp answered it outside the skin: MINIMIZE,
     * SYSMENU, CONTROLMENU, MENU, TRACKINFO (file info), the VIS_ actions and CLOSE on the main
     * window, with the button's param; and TOGGLE of a window the app shows, with the window's
     * name: "ml" (the library), "avs"
     * (the visualizer) or "video", or the id or GUID of another window the skin does not have. With
     * this set, the library, visualizer and video open in the app, not in a frame of the skin.
     * Gets the action, uppercase, and the param or name.
     */
    var onHostAction: ((action: String, param: String?) -> Unit)? = null
    var lastPointer = 0 to 0
    private var dirty = true
    private var needsLayout = true
    private var scriptsStarted = false
    private var lastStatus = player.status

    /**
     * Skin settings by attribute name, as Winamp keeps them in studio.xnf: read when a script makes
     * the attribute, written when it changes. Fill before [load] to start with stored values.
     */
    val storedSettings = mutableMapOf<String, String>()

    /** XML attributes no object handled, as "tag attribute" with a count: what this runtime still lacks. */
    val unhandledParams = sortedMapOf<String, Int>()
    private var lastTitle = player.title
    private var lastVolume = player.volume

    /** The player's shuffle, repeat and crossfade switches as bits 1, 2 and 4. */
    private fun switches(): Int =
        (if (player.shuffle) 1 else 0) or (if (player.repeat) 2 else 0) or (if (player.crossfade) 4 else 0)

    // the switches at the last tick
    private var lastSwitches = switches()

    // the playlist and its current entry at the last tick: a change asks for a frame
    private var lastTracks = player.tracks
    private var lastTrackIndex = player.trackIndex

    // the equalizer as scripts last heard it: changes made elsewhere reach them as System events
    private var lastEqOn = player.eqEnabled
    private var lastPreamp = player.eqPreamp
    private val lastBands = player.eqBands.copyOf()
    private var objects: List<GuiObject> = emptyList()

    /** Every object built, for tests that walk the skin. */
    internal val allObjects: List<GuiObject> get() = objects
    private var captured: GuiObject? = null

    /**
     * The skin format version from skin.xml (`WinampAbstractionLayer version="0.8"`, Winamp Modern
     * 1.35). Winamp draws its built-in list in two ways by it (see [ColorThemeList]).
     */
    var walVersion = 1.0
        private set

    /** Loads `skin.xml` and everything it includes, builds the windows and starts the scripts. */
    fun load() {
        // Winamp's default element set first, when the app has it (files under wasabi-base/): a
        // skin's own definitions then override it, as they do in Winamp
        val baseXml = files.read(LayeredSkinFiles.BASE_PREFIX + "wasabi.xml")?.toString(Charsets.UTF_8)
        if (baseXml != null) {
            val baseNodes = expand(XmlParser.parse(baseXml), LayeredSkinFiles.BASE_PREFIX.trimEnd('/'))
            collectingBase = true
            for (node in baseNodes) collectDefs(prefixFiles(node, LayeredSkinFiles.BASE_PREFIX))
            collectingBase = false
        }
        val rootText = files.read("skin.xml")?.toString(Charsets.UTF_8) ?: error("no skin.xml")
        val root = XmlParser.parse(rootText)
        root
            .firstOrNull { it.name == "winampabstractionlayer" || it.name == "wasabixml" }
            ?.get(
                "version",
            )?.toDoubleOrNull()
            ?.let {
                walVersion =
                    it
            }
        val expanded = expand(root, "")
        for (node in expanded) collectDefs(node)
        for (node in expanded) buildTop(node, null)
        doLayout()
        scriptsStarted = true
        for (s in systems.toList()) startScript(s)
        for (c in containers) c.current?.let { dispatch(it, "onstartup", emptyList()) }
        // the layouts on screen hear that they are, once every script runs (Bento's pledit.maki lays its panes out from this)
        for (c in containers) if (c.visible) c.current?.announceVisible(true)
        ensureLayout()
        objects.filterIsInstance<AnimatedLayer>().forEach { it.startIfAuto() }
        // the main window has the focus from the start, so the first press moves no focus
        if (focused == null) focused = mainLayout
        relayout()
    }

    // ---- resources -------------------------------------------------------------------------

    /** Ids the default set defined and the skin did not; a skin's own definition under either name comes before them. */
    private val baseBitmaps = HashSet<String>()

    /** Groupdefs that came from the default element set. */
    private val baseGroupDefs = HashSet<XmlNode>()

    /**
     * What of the default element set this skin used so far: "bitmap:", "color:", "group:" and
     * "script:" entries. For replacing the default set with our own.
     */
    val baseUsage = sortedSetOf<String>()
    private val baseColors = HashSet<String>()
    private var collectingBase = false

    fun bitmap(id: String): BitmapDef? {
        var fromBase: BitmapDef? = null
        for (name in namesFor(id)) {
            val (key, def) = aliasedBitmap(name) ?: continue
            if (key !in baseBitmaps) return def
            if (fromBase == null) fromBase = def
        }
        fromBase?.let {
            baseUsage.add("bitmap:${it.id}")
            return it
        }
        // a plain file name used as an image
        return if (files.exists(id)) {
            bitmaps.getOrPut(id.lowercase()) { BitmapDef(id.lowercase(), id, 0, 0, null, null) }
        } else {
            null
        }
    }

    /**
     * The bitmap element [name] defines, or names through element aliases (at most 8 hops), with
     * the id it is defined under; null when the chain ends without one.
     */
    private fun aliasedBitmap(name: String): Pair<String, BitmapDef>? {
        var key = name
        var hops = 0
        while (hops++ < 8) {
            val def = bitmaps[key]
            if (def != null) return key to def
            key = aliases[key] ?: return null
        }
        return null
    }

    /**
     * An element id and its old-style twin: Winamp's own objects ask for `wasabi.list.text`, the
     * default set aliases `studio.list.text` to it, and an older skin (MMD3) defines the `studio.`
     * name itself, which Winamp still finds.
     */
    private fun namesFor(id: String): List<String> {
        val k = id.lowercase()
        return when {
            k.startsWith("wasabi.") -> listOf(k, "studio." + k.removePrefix("wasabi."))
            k.startsWith("studio.") -> listOf(k, "wasabi." + k.removePrefix("studio."))
            else -> listOf(k)
        }
    }

    fun bitmapSize(id: String): Pair<Int, Int>? {
        val def = bitmap(id) ?: return null
        if (def.w != null && def.h != null) return def.w to def.h
        val full = host.imageSize(def.file) ?: return null
        return (def.w ?: (full.first - def.x)) to (def.h ?: (full.second - def.y))
    }

    private val resolvedFonts = HashMap<String, BitmapFontDef?>()

    /**
     * Whether the bitmap drawn into [rect] is not fully transparent at a point. Unknown pixels
     * count as opaque, so a host that cannot read pixels keeps rectangle hit testing.
     */
    fun opaqueAt(
        image: String?,
        rect: Rect,
        px: Int,
        py: Int,
    ): Boolean {
        val def = image?.let { bitmap(it) } ?: return true
        val (w, h) = bitmapSize(def.id) ?: return true
        if (rect.width <= 0 || rect.height <= 0) return true
        val sx = def.x + (px - rect.left) * w / rect.width
        val sy = def.y + (py - rect.top) * h / rect.height
        val p = host.pixel(def.file, sx, sy) ?: return true
        return (p ushr 24) != 0
    }

    /** Per bitmap part, its last column with a pixel that shows, or -1 when none does; see [opaqueRight]. */
    private val opaqueColumns = HashMap<String, Int>()

    /**
     * Where [image] drawn into [dst] stops showing on the right: the right edge of its last column
     * with a pixel that is not fully transparent, in layout pixels, or [dst]'s left edge when none
     * shows. A picture whose pixels cannot be read counts as showing to [dst]'s right edge, as
     * unknown pixels count as opaque in [opaqueAt]. The column is looked up once per bitmap part.
     */
    fun opaqueRight(
        image: BitmapDef,
        dst: Rect,
    ): Int {
        if (dst.width <= 0) return dst.left
        val full = host.imageSize(image.file) ?: return dst.right
        val sw = image.w ?: (full.first - image.x)
        val sh = image.h ?: (full.second - image.y)
        // only the part the sheet holds is drawn
        val cw = minOf(sw, full.first - image.x)
        val ch = minOf(sh, full.second - image.y)
        val noSource = cw <= 0 || ch <= 0
        if (image.x < 0 || image.y < 0 || noSource) return dst.left
        val column =
            opaqueColumns.getOrPut("${image.file.lowercase()}|${image.x}|${image.y}|$sw|$sh") {
                if (cw.toLong() * ch > MAX_OPAQUE_SCAN) return@getOrPut sw - 1
                val px = host.pixels(image.file, image.x, image.y, cw, ch) ?: return@getOrPut sw - 1
                (cw - 1 downTo 0).firstOrNull { c -> (0 until ch).any { r -> (px[r * cw + c] ushr 24) != 0 } } ?: -1
            }
        if (column < 0) return dst.left
        // stretched to dst, source column c covers dst pixels up to (c + 1) * dst / source, rounded up
        return minOf(dst.right, dst.left + ((column + 1) * dst.width + sw - 1) / sw)
    }

    /** A bitmap font; its `file` may name a bitmap element, which then gives the file and the offset. */
    fun bitmapFont(id: String): BitmapFontDef? =
        resolvedFonts.getOrPut(id.lowercase()) {
            val def = bitmapFonts[id.lowercase()] ?: return@getOrPut null
            val element = bitmaps[def.file.lowercase()]
            // the color theme reaches a bitmap font only through the bitmap element it draws from;
            // Winamp 5.666 ignores gammagroup on <bitmapfont> itself (MMD3's ticker stays white in
            // every theme, Winamp Modern's turns green in "Good Ol' Winamp")
            if (element != null) {
                BitmapFontDef(
                    def.id,
                    element.file,
                    def.charWidth,
                    def.charHeight,
                    def.hSpacing,
                    def.vSpacing,
                    element.x,
                    element.y,
                    element.gammaGroup,
                )
            } else {
                BitmapFontDef(def.id, def.file, def.charWidth, def.charHeight, def.hSpacing, def.vSpacing)
            }
        }

    fun font(id: String): FontDef? = fonts[id.lowercase()]

    private val colorGroups = HashMap<String, String>()
    private val gammaSets = LinkedHashMap<String, Map<String, Gamma>>()

    /** The color theme in use: the skin's first gammaset until a script or the user picks another. */
    var currentGammaSet: String? = null
        private set

    fun gammaSetNames(): List<String> = gammaSets.keys.toList()

    fun selectGammaSet(name: String) {
        val match = gammaSets.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return
        if (match != currentGammaSet) settingsChanged()
        currentGammaSet = match
        gammas.clear()
        invalidate()
    }

    /** Each gamma group's tint in the color theme worn now, as [gamma] answered it. */
    private val gammas = HashMap<String, Gamma?>()

    fun gamma(group: String?): Gamma? {
        if (group == null) return null
        if (gammas.containsKey(group)) return gammas[group]
        val g = currentGammaSet?.let { gammaSets[it] }?.get(group.lowercase())?.takeUnless { it.isIdentity }
        gammas[group] = g
        return g
    }

    fun color(
        id: String,
        depth: Int = 0,
    ): Rgb? {
        val key = colorKey(id) ?: return null
        if (key in baseColors) baseUsage.add("color:$key")
        // an alias takes the other color's RGB, tinted by its own group (or the other's when it has none)
        val c =
            colors[key] ?: colorAliases[key]?.takeIf { depth < MAX_ALIAS_DEPTH }?.let { plainColor(it, depth + 1) } ?: return null
        // a color that names no gammagroup takes the theme's "Text" group (Wasabi SDK skinclr)
        val g = gamma(colorGroups[key] ?: colorAliases[key]?.let { colorGroups[it] } ?: "text") ?: return c
        return Rgb(g.apply(c.value or 0xff000000.toInt()) and 0xffffff)
    }

    /** A color's RGB before any tint, following aliases. */
    private fun plainColor(
        id: String,
        depth: Int,
    ): Rgb? {
        val key = colorKey(id) ?: return null
        return colors[key] ?: colorAliases[key]?.takeIf { depth < MAX_ALIAS_DEPTH }?.let { plainColor(it, depth + 1) }
    }

    /**
     * The name a color is defined under, as an RGB or an alias: [id] or its old-style twin, a skin's
     * definition before the default set's. Null when neither is defined.
     */
    private fun colorKey(id: String): String? {
        val names = namesFor(id).filter { it in colors || it in colorAliases }
        return names.firstOrNull { it !in baseColors } ?: names.firstOrNull()
    }

    fun container(id: String): Container? = containers.firstOrNull { it.id.equals(id, ignoreCase = true) }

    val mainContainer: Container? get() = container("main") ?: containers.firstOrNull()
    val mainLayout: Layout? get() = mainContainer?.current

    /**
     * Windows a skin button opened (playlist, color themes, ...), in the order they opened. A phone
     * shows them stacked under the main window, as Winamp's docked windows sit: each as wide as the
     * main window, the last one filling the height that is left when it shows a list (see
     * [viewSize] and [stretchLast]).
     */
    private val opened = ArrayList<Container>()

    /** The windows on screen, top to bottom: the main one first. */
    val windows: List<Layout>
        get() {
            if (windowsAt != changeCount) {
                windowsNow = listOfNotNull(mainLayout) + opened.mapNotNull { it.current }
                windowsAt = changeCount
            }
            return windowsNow
        }

    // a window opens, closes or switches its layout only with a relayout, which [changeCount] counts
    private var windowsNow: List<Layout> = emptyList()
    private var windowsAt = -1L

    /** The window opened last, which Back puts away; null when only the main window is open. */
    val topWindow: Container? get() = opened.lastOrNull()

    /** The space the app has for the stack, in layout pixels; null leaves windows at their own size. */
    var viewSize: Pair<Int, Int>? = null
        set(value) {
            if (value != field) {
                field = value
                relayout()
            }
        }

    /**
     * Whether the last docked window takes the height [viewSize] leaves under the others, when it
     * can be made taller and shows a list, which then shows more rows (see [showsList]); an
     * equalizer or a visualizer frame keeps the height the skin gives it. Off, every window keeps
     * the height the skin gives it (a phone held sideways).
     */
    var stretchLast = true
        set(value) {
            if (value != field) {
                field = value
                relayout()
            }
        }

    private val placed = HashMap<Layout, Rect>()

    /** The window with the focus: the one touched last, the main one at the start. */
    private var focused: Layout? = null

    fun hasFocus(l: Layout?): Boolean = l != null && l === (focused?.takeIf { it in windows } ?: mainLayout)

    /** Where [layout] sits in the stack, in layout pixels. */
    fun windowRect(layout: Layout): Rect = placed[layout] ?: Rect(0, 0, layout.size.first, layout.size.second)

    /**
     * Whether a skin window is open: on a phone (a [viewSize]), when it is in the stack, whatever
     * its own visibility says (Winamp3's playlist window starts visible, as a window beside the
     * main one); without one, as the window itself says.
     */
    fun isOpen(c: Container): Boolean = if (viewSize == null) c.visible else c === mainContainer || c in opened

    /** Containers shown at least once: showing one again gives it the focus. */
    private val shownBefore = HashSet<Container>()

    fun openWindow(c: Container) {
        if (c === mainContainer || c in opened) return
        opened.add(c)
        shownBefore.add(c)
        c.show()
        relayout()
    }

    fun closeWindow(c: Container) {
        if (!opened.remove(c)) return
        c.hide()
        relayout()
    }

    /** Gives [l] the focus: a layout switched to takes it. */
    fun focus(l: Layout) {
        focused = l
    }

    /**
     * Gives [l] the focus as a touch does: the window losing it and [l] show their alphas and
     * pictures for it (activealpha and inactivealpha, a layer's inactiveimage) on the next frame,
     * with nothing moved or resized, so no layout pass runs and no object hears onResize.
     */
    private fun takeFocus(l: Layout) {
        if (l === focused) return
        val had = focused?.takeIf { it in windows } ?: mainLayout
        focused = l
        if (had === l) return
        had?.refreshAlphas()
        l.refreshAlphas()
        invalidate()
    }

    /** A script's Container.show / hide: the same as a button's, into the phone's stack and out. */
    fun showContainer(
        c: Container,
        show: Boolean,
    ) {
        if (!show && !c.canClose) return
        when {
            viewSize == null -> if (show) c.show() else c.hide()
            show -> openWindow(c)
            else -> closeWindow(c)
        }
    }

    fun closeTopWindow() {
        topWindow?.let { closeWindow(it) }
    }

    /**
     * How many columns the stack is set out in once the app gives it a [viewSize]; a change lays the
     * windows out again. At 2 [StackArrange] puts the windows that show a list in a column right of
     * the main window, else every window docks under the main one.
     */
    var columns = 1
        set(value) {
            if (value != field) {
                field = value
                relayout()
            }
        }

    /**
     * How wide the windows in the right column of two [columns] are, in layout pixels; 0 for as
     * wide as what shows of the main window. A change lays the windows out again.
     */
    var columnWidth = 0
        set(value) {
            if (value != field) {
                field = value
                relayout()
            }
        }

    /** Stacks the windows as [StackArrange.arrange] sets them out: the main one at its size, the others under it. */
    private fun arrange() {
        placed.clear()
        val main = mainLayout ?: return
        val ask = StackArrange.Ask(main, windows.drop(1), viewSize, stretchLast, columns, ::showsList, columnWidth)
        placed.putAll(StackArrange.arrange(ask))
    }

    /**
     * Whether [l] shows a list, which shows more rows when the window is taller: Winamp's playlist
     * editor or media library, named by the window's container (`component="guid:..."`, or `hold`
     * or `param`) or held by a component or window holder anywhere in the window, or a color theme
     * list. Equalizers, visualizer frames and windows of fixed controls show none. [StackArrange]
     * stretches only such a window.
     */
    fun showsList(l: Layout): Boolean {
        fun holdsList(attrs: Map<String, String>) = LIST_GUIDS.any { holds(attrs, it) }

        fun walk(o: GuiObject): Boolean =
            when (o) {
                is PlaylistView, is ColorThemeList -> true
                is Group -> (o.tag in HOLDER_TAGS && holdsList(o.attrs)) || o.children.any { walk(it) }
                else -> false
            }
        return l.container?.let { holdsList(it.attrs) } == true || l.children.any { walk(it) }
    }

    /** The stack's size, in layout pixels. */
    val stackSize: Pair<Int, Int>
        get() {
            if (needsLayout) doLayout()
            if (stackAt != changeCount) {
                val rects = windows.map { windowRect(it) }
                stackNow = (rects.maxOfOrNull { it.right } ?: 0) to (rects.maxOfOrNull { it.bottom } ?: 0)
                stackAt = changeCount
            }
            return stackNow
        }

    // worked out once for each change (see [changeCount]), as a frame asks for them several times
    private var stackNow = 0 to 0
    private var stackAt = -1L
    private var drawnNow = 0 to 0
    private var drawnAt = -1L

    /**
     * The part of the stack its windows show, in layout pixels: [stackSize] with each window cut on
     * its right to where it shows ([Layout.contentRight]), as MMD3's main window without the room
     * its closed drawers keep, and on its bottom to the part Winamp docks and snaps to
     * ([Layout.snappedSize]), as Winamp Modern's without the room its closed drawer keeps. A drawer
     * sliding out counts as far as it shows.
     */
    val drawnSize: Pair<Int, Int>
        get() {
            if (needsLayout) doLayout()
            if (drawnAt != changeCount) {
                var right = 0
                var bottom = 0
                for (l in windows) {
                    val r = windowRect(l)
                    right = maxOf(right, r.left + l.contentRight())
                    bottom = maxOf(bottom, r.top + l.snappedSize().second)
                }
                drawnNow = right to bottom
                drawnAt = changeCount
            }
            return drawnNow
        }

    /** Total length of the playlist entries selected in the playlist editor, for `display="pe_info"`. */
    var playlistSelectedMs = 0

    // ---- XML -------------------------------------------------------------------------------

    private fun dirOf(path: String) = path.substringBeforeLast('/', "")

    /** The same tree with every `file` attribute under [prefix]: the default set's pictures live there. */
    private fun prefixFiles(
        node: XmlNode,
        prefix: String,
    ): XmlNode {
        val attrs =
            if (node.name in FILE_NODES &&
                node["file"] != null
            ) {
                node.attrs + ("file" to prefix + node["file"]!!.replace('\\', '/').trimStart('/'))
            } else {
                node.attrs
            }
        return XmlNode(node.name, attrs, node.children.map { prefixFiles(it, prefix) }, node.text)
    }

    /**
     * Winamp's placeholders in attribute values: `@HAVE_LIBRARY@` is "1" when the Media Library is
     * there (it is on the reference), `@SKINSPATH@` the skins folder (resolved by the files).
     */
    private fun substituted(attrs: Map<String, String>): Map<String, String> =
        if (attrs.values.none { it.contains("@HAVE_") }) {
            attrs
        } else {
            attrs.mapValues { (_, v) -> v.replace("@HAVE_LIBRARY@", if (haveLibrary) "1" else "0").replace("@HAVE_VIDEO@", "1") }
        }

    /** Nodes made by expanding includes so far: a skin that includes itself in circles stops here. */
    private var expandedNodes = 0

    /**
     * Replaces every `<include>` by the nodes of the file it names, relative to the including file.
     * A file that includes itself (directly or round about), includes nested past
     * [MAX_INCLUDE_DEPTH] and anything past [MAX_NODES] nodes are left out.
     */
    private fun expand(
        nodes: List<XmlNode>,
        dir: String,
        chain: List<String> = emptyList(),
        depth: Int = 0,
    ): List<XmlNode> =
        nodes.flatMap { n ->
            if (++expandedNodes > MAX_NODES || depth > MAX_XML_DEPTH) {
                if (expandedNodes == MAX_NODES + 1) host.log("skin too large: stopped at $MAX_NODES nodes")
                return@flatMap emptyList()
            }
            if (n.name == "include") {
                val file = n["file"]?.replace('\\', '/')?.removePrefix("@DEFAULTSKINPATH@") ?: return@flatMap emptyList()
                val candidates = listOf(if (dir.isEmpty()) file else "$dir/$file", file)
                val path = candidates.firstOrNull { files.exists(it) }
                when {
                    path == null -> {
                        host.log("include not found: $file (from $dir)")
                        emptyList()
                    }

                    path in chain || chain.size >= MAX_INCLUDE_DEPTH -> {
                        host.log("include skipped: $file (includes itself or nests too deep)")
                        emptyList()
                    }

                    else -> {
                        val text = files.read(path)!!.toString(Charsets.UTF_8)
                        expand(XmlParser.parse(text), dirOf(path), chain + path, depth + 1)
                    }
                }
            } else {
                listOf(XmlNode(n.name, substituted(n.attrs), expand(n.children, dir, chain, depth + 1), n.text))
            }
        }

    @Suppress("CyclomaticComplexMethod") // one arm per definition tag
    private fun collectDefs(node: XmlNode) {
        when (node.name) {
            "bitmap" -> {
                node["id"]?.let { id ->
                    val given = node["file"] ?: return
                    // a picture Winamp makes itself: a made-up name the hosts' files answer
                    val file =
                        if (given.substringAfterLast('/').equals(
                                "\$solid",
                                ignoreCase = true,
                            )
                        ) {
                            GeneratedImages.solidName(
                                node["w"]?.toIntLenient(),
                                node["h"]?.toIntLenient(),
                                node["color"],
                                node["alpha"]?.toIntLenient(),
                            )
                        } else {
                            given
                        }
                    if (collectingBase) baseBitmaps.add(id.lowercase()) else baseBitmaps.remove(id.lowercase())
                    bitmaps[id.lowercase()] =
                        BitmapDef(
                            id.lowercase(),
                            file,
                            node["x"]?.toIntLenient() ?: 0,
                            node["y"]?.toIntLenient() ?: 0,
                            node["w"]?.toIntLenient(),
                            node["h"]?.toIntLenient(),
                            node["gammagroup"]?.lowercase(),
                        )
                }
            }

            "bitmapfont" -> {
                node["id"]?.let { id ->
                    val file = node["file"] ?: return
                    bitmapFonts[id.lowercase()] =
                        BitmapFontDef(
                            id.lowercase(),
                            file,
                            node["charwidth"]?.toIntLenient() ?: 5,
                            node["charheight"]?.toIntLenient() ?: 6,
                            node["hspacing"]?.toIntLenient() ?: 0,
                            node["vspacing"]?.toIntLenient() ?: 0,
                            gammaGroup = node["gammagroup"]?.lowercase(),
                        )
                }
            }

            "truetypefont" -> {
                node["id"]?.let { id -> node["file"]?.let { fonts[id.lowercase()] = FontDef(id.lowercase(), it) } }
            }

            "color" -> {
                node["id"]?.let { id ->
                    if (collectingBase) baseColors.add(id.lowercase()) else baseColors.remove(id.lowercase())
                    node["value"]?.let { v -> defineColor(id.lowercase(), v) }
                    node["gammagroup"]?.let { colorGroups[id.lowercase()] = it.lowercase() }
                }
            }

            "gammaset" -> {
                node["id"]?.let { id ->
                    gammaSets[id] =
                        node.children.filter { it.name == "gammagroup" }.associate { g ->
                            val v = (g["value"] ?: "0,0,0").split(',').map { it.trim().toIntOrNull() ?: 0 }
                            (g["id"] ?: "").lowercase() to
                                Gamma(
                                    v.getOrElse(0) { 0 },
                                    v.getOrElse(1) { 0 },
                                    v.getOrElse(2) { 0 },
                                    g["boost"]?.toIntLenient() ?: 0,
                                    g["gray"]?.toIntLenient() ?: 0,
                                )
                        }
                    if (currentGammaSet == null) currentGammaSet = id
                    gammas.clear()
                }
            }

            "elementalias" -> {
                node["id"]?.let { id -> node["target"]?.let { aliases[id.lowercase()] = it.lowercase() } }
            }

            "groupdef" -> {
                node["id"]?.let { id ->
                    groupDefs.put(id.lowercase(), node)?.let { earlierDefs[node] = it }
                    if (collectingBase) baseGroupDefs.add(node)
                    node["xuitag"]?.let { xuiTags[it.lowercase()] = id.lowercase() }
                }
            }

            // getSkinName() is the skin's folder name, which the host sets; <skininfo> names it only when it did not
            "skininfo" -> {
                if (skinName.isEmpty()) {
                    // the name is the element's text, <name>Bento</name>, or else a text attribute
                    node.children.firstOrNull { it.name == "name" }?.let {
                        skinName = it.text.ifEmpty { it["text"] ?: skinName }
                    }
                }
            }
        }
        for (c in node.children) collectDefs(c)
    }

    /**
     * A `<color>`'s value under the lowercase id [k]. A value that is no RGB names another color
     * (Bento's wasabi.list.background = color.display.bg); the later definition wins in either form
     * (a skin's alias over the default set's RGB).
     */
    private fun defineColor(
        k: String,
        v: String,
    ) {
        val rgb = parseRgb(v)
        if (rgb != null) {
            colors[k] = rgb
            colorAliases.remove(k)
        } else {
            colorAliases[k] = v.lowercase()
            colors.remove(k)
        }
    }

    private fun buildTop(
        node: XmlNode,
        parent: Group?,
    ) {
        when (node.name) {
            "wasabixml", "winampabstractionlayer", "elements", "scripts", "skininfo", "gammaset", "accelerators" -> {
                val walkInto = node.name == "scripts" || node.name !in setOf("elements", "skininfo", "gammaset", "accelerators")
                if (walkInto) for (c in node.children) buildTop(c, parent)
            }

            "container" -> {
                buildContainer(node)
            }

            "script" -> {
                attachScript(node, parent)
            }

            "groupdef", "bitmap", "bitmapfont", "truetypefont", "color", "elementalias",
            "colorthemes:list", "colorthemes:mgr",
            -> {
                Unit
            }

            else -> {
                host.log("top-level <${node.name}> ignored")
            }
        }
    }

    private fun buildContainer(node: XmlNode) {
        val id = node["id"]?.lowercase() ?: return
        val container = Container(this, id, node.attrs)
        containers.add(container)
        for (c in node.children) {
            when (c.name) {
                "layout" -> {
                    val layout = Layout(this)
                    layout.container = container
                    fillGroup(layout, c)
                    container.layouts.add(layout)
                }

                "script" -> {
                    attachScript(c, null)
                }

                "scripts" -> {
                    for (s in c.children) attachScript(s, null)
                }

                "groupdef", "scripts" -> {
                    Unit
                }

                else -> {
                    host.log("<${c.name}> in container $id ignored")
                }
            }
        }
        container.current = container.layouts.firstOrNull { it.id == "normal" } ?: container.layouts.firstOrNull()
        // a new main window, for what is worked out from the windows
        changeCount++
    }

    /** Applies a groupdef, its inherited chain first, then the node's own attributes and children. */
    private fun fillGroup(
        group: Group,
        node: XmlNode,
        defId: String? = node["id"],
    ) {
        val meta = ArrayList<Pair<XmlNode, Int>>()
        if (defId != null) applyGroupDef(group, defId.lowercase(), meta)
        group.setParams(node.attrs)
        for (c in node.children) buildChild(c, group, meta)
        applyMeta(group, meta)
    }

    private fun applyGroupDef(
        group: Group,
        defId: String,
        meta: MutableList<Pair<XmlNode, Int>>,
    ) {
        groupDefs[defId]?.let { applyGroupDef(group, it, ALL_CONTENT, meta, HashSet()) }
    }

    /**
     * Builds [def] into [group], only the [want]ed parts ("params", "xui" objects, "scripts"). The
     * base comes first: `inherit_group`, else the groupdef this one redefined. What of the base is
     * used is `inherit_content` ("1" all, "0" none, or a list; objects and scripts by default with an
     * `inherit_group`, nothing without), plus its params unless `inherit_params="0"`.
     */
    private fun applyGroupDef(
        group: Group,
        def: XmlNode,
        want: Set<String>,
        meta: MutableList<Pair<XmlNode, Int>>,
        seen: MutableSet<XmlNode>,
    ) {
        if (!seen.add(def)) return
        if (def in baseGroupDefs) baseUsage.add("group:${def["id"]?.lowercase()}")
        GroupBehavior.of(this, group, def)?.let { group.behavior = it }
        val named = def["inherit_group"]?.lowercase()?.takeIf { it != def["id"]?.lowercase() }
        val base = named?.let { groupDefs[it] } ?: earlierDefs[def]
        if (base != null) {
            val content =
                when (val c = def["inherit_content"]?.trim()?.lowercase()) {
                    null -> if (named != null) setOf("xui", "scripts") else emptySet()
                    "1" -> setOf("xui", "scripts")
                    "0" -> emptySet()
                    else -> c.split(';', ',').map { it.trim() }.toSet()
                }
            val inherited = content + if (def["inherit_params"]?.trim() == "0") emptySet() else setOf("params")
            applyGroupDef(group, base, want intersect inherited, meta, seen)
        }
        if ("params" in want) def.attrs.forEach { (k, v) -> if (k !in DEF_ATTRS) group.setParam(k, v) }
        for (c in def.children) if ((if (c.name == "script") "scripts" else "xui") in want) buildChild(c, group, meta)
    }

    /**
     * Whether a component, a window holder or a container with the attributes [attrs] shows the
     * component [guid], named by its `hold`, `param` or `component` attribute: by GUID or its alias.
     */
    private fun holds(
        attrs: Map<String, String>,
        guid: String,
    ): Boolean =
        listOf("hold", "param", "component").any { k ->
            attrs[k]?.split(';')?.any { v ->
                val id =
                    v
                        .trim()
                        .lowercase()
                        .removePrefix("guid:")
                        .trim('{', '}')
                id == guid || TOGGLE_GUIDS[id] == guid
            } == true
        }

    /** GUI objects built so far: a skin that makes them without end is cut off at [MAX_OBJECTS]. */
    private var built = 0

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount") // one arm per XML tag; tags that place no object return
    private fun buildChild(
        node: XmlNode,
        parent: Group,
        meta: MutableList<Pair<XmlNode, Int>>,
    ) {
        if (++built > MAX_OBJECTS) {
            if (built == MAX_OBJECTS + 1) host.log("skin too large: stopped at $MAX_OBJECTS objects")
            return
        }
        // Wasabi's own tags (Wasabi:Button, Wasabi:Text, ...) are xui groups of the default set when it
        // defines them: a framed button inside a group, and so on
        if (node.name.startsWith("wasabi:") && node.name in xuiTags) {
            buildXui(node, parent, xuiTags.getValue(node.name))
            return
        }
        val obj: GuiObject? =
            when (node.name) {
                "layer" -> {
                    Layer(this)
                }

                "animatedlayer" -> {
                    AnimatedLayer(this)
                }

                "button", "wasabi:button" -> {
                    Button(this, node.name)
                }

                "nstatesbutton" -> {
                    NStatesButton(this)
                }

                "togglebutton" -> {
                    ToggleButton(this)
                }

                "text", "wasabi:text" -> {
                    Text(this, node.name)
                }

                // a songticker is a text that shows the song name and its length unless told otherwise
                // (Winamp Modern's shaded playlist ticker sets no showlen and shows "(10:00)")
                "songticker" -> {
                    Text(this, node.name).also {
                        it.songTicker = true
                        it.valign = VAlign.TOP
                        it.display = "songname"
                        it.ticker = true
                        it.showLen = true
                    }
                }

                "slider", "wasabi:hslider", "wasabi:vslider" -> {
                    Slider(this)
                }

                "status" -> {
                    Status(this)
                }

                "layoutstatus" -> {
                    LayoutStatus(this)
                }

                "grid" -> {
                    Grid(this)
                }

                "progressgrid" -> {
                    ProgressGrid(this)
                }

                "vis" -> {
                    Vis(this)
                }

                "colorthemes:list" -> {
                    ColorThemeList(this)
                }

                "albumart" -> {
                    AlbumArt(this)
                }

                "rect" -> {
                    RectObj(this)
                }

                "gradient" -> {
                    Gradient(this)
                }

                "images" -> {
                    Images(this)
                }

                // Winamp's own playlist editor, which a skin embeds as a component
                // hold, param and component name what the holder shows, all the same
                "component", "windowholder" -> {
                    if (holds(node.attrs, PLAYLIST_GUID)) {
                        PlaylistView(this)
                    } else {
                        val g = Group(this, node.name)
                        fillGroup(g, node)
                        parent.addChild(g)
                        return
                    }
                }

                "eqvis" -> {
                    EqVis(this)
                }

                "wasabi:frame", "frame" -> {
                    val f = FrameSplitter(this)
                    fillGroup(f, node, defId = null)
                    parent.addChild(f)
                    return
                }

                // a window holder for Winamp's playlist editor is the playlist view as well (Bento)
                "group", "cfggroup", "groupxfade", "componentbucket" -> {
                    val g = Group(this, node.name)
                    fillGroup(g, node)
                    parent.addChild(g)
                    return
                }

                "layout" -> {
                    host.log("nested <layout> in ${parent.id} ignored")
                    return
                }

                "script" -> {
                    attachScript(node, parent)
                    return
                }

                "scripts" -> {
                    for (c in node.children) attachScript(c, parent)
                    return
                }

                "sendparams", "hideobject", "showobject" -> {
                    // with how many children the group had then: it reaches only objects made before it
                    meta.add(node to parent.children.size)
                    return
                }

                "bitmap", "bitmapfont", "color", "truetypefont", "elementalias", "elements", "groupdef",
                "gammaset", "snappoint", "accelerators", "include",
                -> {
                    return
                }

                else -> {
                    val defId = xuiTags[node.name]
                    if (defId != null) {
                        buildXuiWithContent(node, parent, defId)
                        return
                    }
                    val className = KNOWN_CLASSES[node.name] ?: "GuiObject"
                    if (node.name.contains(':') ||
                        node.name !in KNOWN_CLASSES
                    ) {
                        host.log("<${node.name}> unknown, placed as $className")
                    }
                    Unknown(this, node.name, className)
                }
            }
        obj?.let {
            it.setParams(node.attrs)
            parent.addChild(it)
        }
    }

    /**
     * A xui element (see [buildXui]) with the group its `content` attribute names inside it, when
     * no script of the element runs: the standard frame scripts build the content group
     * themselves, but only when they run.
     */
    private fun buildXuiWithContent(
        node: XmlNode,
        parent: Group,
        defId: String,
    ) {
        val g = buildXui(node, parent, defId)
        val content = node["content"] ?: return
        if (g.systemObjects.isNotEmpty()) return
        val inner = Group(this, "group")
        fillGroup(
            inner,
            XmlNode(
                "group",
                mapOf(
                    "id" to content,
                    "x" to "0",
                    "y" to "0",
                    "w" to "0",
                    "h" to "0",
                    "relatw" to "1",
                    "relath" to "1",
                ),
                emptyList(),
            ),
        )
        g.addChild(inner)
    }

    /**
     * A xui element: a group made from the definition that names [node]'s tag. Its GUI attributes
     * place the group; the others go to its scripts as xui parameters and to the child the group
     * embeds (Wasabi:Button's image, action and text reach its button).
     */
    private fun buildXui(
        node: XmlNode,
        parent: Group,
        defId: String,
    ): Group {
        val g = Group(this, node.name)
        val gui = HashSet(GUI_ATTRS)
        fillGroup(g, XmlNode(node.name, node.attrs.filterKeys { it in gui }, node.children, node.text), defId)
        val rest = node.attrs.filterKeys { it !in gui }
        rest.forEach { (k, v) -> g.xuiParams[k] = v }
        g.embedded?.setParams(rest)
        parent.addChild(g)
        if (groupDefs[defId] == null) host.log("xui ${node.name}: no groupdef $defId")
        return g
    }

    /**
     * `<hideobject>`, `<showobject>`, `<sendparams>`: applied where they stand in the XML, so they
     * reach the objects made before them (Winamp3's `hideobject target="close"` hides the frame's
     * close buttons, not its own exit buttons of the same name further down).
     */
    private fun applyMeta(
        group: Group,
        meta: List<Pair<XmlNode, Int>>,
    ) {
        for ((m, before) in meta) {
            val targets = m["target"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: continue
            val existing = group.children.take(before)
            val scope = m["group"]?.let { findMetaTarget(existing, it) as? Group }
            for (t in targets) {
                val target = (if (scope != null) scope.findObject(t) else findMetaTarget(existing, t)) ?: continue
                applyMetaTo(m, target)
            }
        }
    }

    /** The object [id] names among [objs], by id or instance id, else anywhere inside one of their groups. */
    private fun findMetaTarget(
        objs: List<GuiObject>,
        id: String,
    ): GuiObject? {
        val lower = id.lowercase()
        objs.firstOrNull { it.id == lower || (it is Group && it.instanceId == lower) }?.let { return it }
        for (o in objs) if (o is Group) o.findObject(lower)?.let { return it }
        return null
    }

    /** What one `<hideobject>`, `<showobject>` or `<sendparams>` [m] does to [target]. */
    private fun applyMetaTo(
        m: XmlNode,
        target: GuiObject,
    ) {
        when (m.name) {
            "hideobject" -> target.hide()
            "showobject" -> target.show()
            "sendparams" -> m.attrs.forEach { (k, v) -> if (k != "target" && k != "group") target.setParam(k, v) }
        }
    }

    private fun attachScript(
        node: XmlNode,
        group: Group?,
    ) {
        val file = node["file"] ?: return
        if (file.startsWith(
                LayeredSkinFiles.BASE_PREFIX,
            )
        ) {
            baseUsage.add("script:${file.removePrefix(LayeredSkinFiles.BASE_PREFIX)}")
        }
        val program =
            programs.getOrPut(file.lowercase()) {
                val bytes = files.read(file) ?: files.read("scripts/" + file.substringAfterLast('/'))
                if (bytes == null) {
                    host.log("script not found: $file")
                    null
                } else {
                    // MakiParser.parse reports any damaged file as a MakiFormatException
                    try {
                        MakiParser.parse(bytes, file)
                    } catch (e: MakiFormatException) {
                        host.log("script $file: ${e.message}")
                        null
                    }
                }
            } ?: return
        val system = SystemObject(this, group, node["param"] ?: "", file)
        val script = Script(program, system, this)
        scripts.add(script)
        systems.add(system)
        group?.systemObjects?.add(system)
        // a script in a group made later starts once that group has been laid out
        if (scriptsStarted) {
            pendingStarts.add(system)
            relayout()
        }
    }

    /** `System.newGroup(id)`: a fresh instance of a groupdef, not yet in any parent. */
    fun instantiateGroup(id: String): Group? {
        if (groupDefs[id.lowercase()] == null) return null
        val g = Group(this, "group")
        fillGroup(g, XmlNode("group", mapOf("id" to id), emptyList()))
        return g
    }

    // ---- MakiHost ----------------------------------------------------------------------------

    /** Objects scripts made with `new` (timers, lists, ...), cut off at [MAX_CREATED]. */
    private var created = 0

    override fun create(guid: String): MakiObject? {
        val cls = StdApi.byGuid[guid.lowercase()] ?: return null
        if (++created > MAX_CREATED) {
            if (created == MAX_CREATED + 1) host.log("scripts made more than $MAX_CREATED objects; no more")
            return null
        }
        return when (cls.name.lowercase()) {
            "timer" -> Timer(this)
            "popupmenu" -> PopupMenu(this)
            "map" -> MakiMap(this)
            "list" -> MakiList()
            "config" -> ConfigObj(this)
            "winampconfig" -> WinampConfigObj(this)
            "group" -> Group(this)
            "layer" -> Layer(this)
            "application" -> ApplicationObj()
            else -> Stub(guid.lowercase(), cls.name)
        }
    }

    override fun log(message: String) = host.log(message)

    override fun dispatchEvent(
        target: MakiObject,
        event: String,
        args: List<Value>,
    ): Value = dispatchForResult(target, event, args)

    /** Fires [event] and gives back what the last handler that ran returned (Null when none ran). */
    fun dispatchForResult(
        target: MakiObject,
        event: String,
        args: List<Value>,
    ): Value {
        if (!enterEvent(event)) return Value.Null
        var result: Value = Value.Null
        try {
            for (s in scripts.toList()) {
                if (event in s.events && s.dispatch(target, event, args) > 0) result = s.lastReturn
            }
        } finally {
            eventDepth--
        }
        if (target is GuiObject) {
            val p = target.parent
            if (p is Group &&
                p.embedXui == target.id
            ) {
                dispatchForResult(p, event, args).takeIf { it != Value.Null }?.let { result = it }
            }
        }
        return result
    }

    /**
     * The clock every timer, animation and script reads, in ms. The app leaves the wall clock; a
     * test steps its own so animations and timers land on the same frame every run.
     */
    var clock: () -> Long = System::currentTimeMillis

    fun now(): Long = clock()

    /** How far the phone's local time is from UTC at a moment of [clock], in ms: the time of day scripts read is local. */
    var zoneOffset: (Long) -> Int = {
        java.util.TimeZone
            .getDefault()
            .getOffset(it)
    }

    fun invalidate() {
        dirty = true
        changeCount++
    }

    /**
     * Asks for a frame for a change that moves, resizes, shows, hides and reshapes nothing: a
     * strip's next picture, a slider's thumb, a text, a pressed button, an alpha that stays above 0.
     * What is worked out from the windows and their shapes (see [changeCount]) is kept.
     */
    fun repaint() {
        dirty = true
    }

    fun relayout() {
        needsLayout = true
        dirty = true
        changeCount++
    }

    /**
     * Counts [invalidate] and [relayout] calls and layout passes: what the windows are, their shapes
     * and the edges worked out from them ([Layout.contentRight], [drawnSize] and the like) are kept
     * until it moves on. A visualizer, a ticker, a time or anything else that asks with [repaint]
     * changes none of these, and gets its frame without it.
     */
    var changeCount = 0L
        private set

    /** Window shapes worked out so far (see [changeCount]). */
    var shapesWorkedOut = 0L
        internal set

    /** Lays the main window out now, for a script that asks where something is. */
    fun ensureLayout() {
        if (needsLayout) doLayout()
    }

    private val lastRects = HashMap<GuiObject, Rect>()
    private val pendingStarts = ArrayList<SystemObject>()

    /** Lays out until scripts stop changing the tree (a few passes at most). */
    private fun doLayout() {
        var passes = 0
        do {
            layoutPass()
        } while (needsLayout && ++passes < MAX_LAYOUT_PASSES)
    }

    /** Layout passes run so far. */
    var layoutPasses = 0L
        private set

    private fun layoutPass() {
        layoutPasses++
        needsLayout = false
        changeCount++
        skinMenus.clear()
        arrange()
        for (l in windows) l.layoutRoot()
        val found = Found()
        for (l in windows) found.walk(l)
        val all = found.all
        val changed = all.size != objects.size || all.indices.any { all[it] !== objects[it] }
        objects = all
        sliders = found.sliders
        texts = found.texts
        visObjects = found.visObjects
        progressGrids = found.grids
        buttons = found.buttons
        // an object gone from every window, such as a group a script dropped, is let go of
        if (changed) lastRects.keys.removeAll { it.layoutObject?.container == null }
        // the player's sliders show where the player is from their first frame: at the load, and in
        // a window or layout shown for the first time, which no tick has synced yet
        for (o in all) if (o is Slider) o.placeIfUnsynced()
        if (pendingStarts.isNotEmpty()) {
            val starting = pendingStarts.toList()
            pendingStarts.clear()
            for (s in starting) startScript(s)
            // scripts that just started have not heard any sizes yet
            lastRects.clear()
        }
        for (o in all) if (o is Group) deliverXuiParams(o)
        for (o in all) if (o is Group) o.behavior?.afterLayout()
        // objects whose place or size changed hear about it, as Wasabi calls onResize on every move
        // (Bento's pledit.maki hides its playlist time from the text's own onResize)
        if (scriptsStarted) {
            for (o in all) {
                val r = o.rect
                if (lastRects[o] != r) {
                    lastRects[o] = r
                    dispatch(o, "onresize", listOf(Value.Int(r.left), Value.Int(r.top), Value.Int(r.width), Value.Int(r.height)))
                }
            }
        }
    }

    /** Runs a script's onScriptLoaded; its group's xui parameters follow at once, before any later script starts. */
    private fun startScript(s: SystemObject) {
        s.started = true
        dispatch(s, "onscriptloaded", emptyList())
        s.group?.let { deliverXuiParams(it) }
    }

    /**
     * A xui element's parameters reach its scripts once they all ran onScriptLoaded, where they
     * look their objects up.
     */
    fun deliverXuiParams(g: Group) {
        if (g.xuiParams.isEmpty()) return
        // a default-set group the engine runs hears its parameters at once
        g.behavior?.let { b ->
            val params = g.xuiParams.toList()
            g.xuiParams.clear()
            for ((k, v) in params) b.onXuiParam(k, v)
            return
        }
        if (g.systemObjects.isEmpty() || !g.systemObjects.all { it.started }) return
        val params = g.xuiParams.toList()
        g.xuiParams.clear()
        for ((k, v) in params) for (s in g.systemObjects) dispatch(s, "onsetxuiparam", listOf(Value.Str(k), Value.Str(v)))
    }

    /** How many events are firing inside each other right now. */
    private var eventDepth = 0

    /**
     * Whether another event may fire inside the ones already firing: a handler that fires an event
     * whose handler fires it back would otherwise never end.
     */
    private fun enterEvent(event: String): Boolean {
        if (eventDepth >= MAX_EVENT_DEPTH) {
            host.log("event $event not fired: events nested $eventDepth deep")
            return false
        }
        eventDepth++
        return true
    }

    /** Fires [event] on [target] in every script. */
    fun dispatch(
        target: MakiObject,
        event: String,
        args: List<Value>,
    ): Int {
        if (!enterEvent(event)) return 0
        var n = 0
        try {
            // a copy: a handler may load another script
            for (s in scripts.toList()) if (event in s.events) n += s.dispatch(target, event, args)
        } finally {
            eventDepth--
        }
        // what happens to the child a xui group exposes happens to the group
        if (target is GuiObject) {
            val p = target.parent
            if (p is Group && p.embedXui == target.id) n += dispatch(p, event, args)
        }
        return n
    }

    // a script hears System events only once it has run onScriptLoaded (Wasabi SDK objcontroller)
    private fun dispatchSystem(
        event: String,
        vararg args: Value,
    ) {
        for (s in systems.toList()) if (s.started) dispatch(s, event, args.toList())
    }

    // ---- per frame ---------------------------------------------------------------------------

    /** Whether something moves by itself right now: a target slide, or an animation playing and not paused. */
    val animating: Boolean get() = moving.isNotEmpty() || animations.any { it.running }

    /** Every object of a window, in drawing order, and apart those that move with the player or the clock. */
    private class Found {
        val all = ArrayList<GuiObject>()
        val sliders = ArrayList<Slider>()
        val texts = ArrayList<Text>()
        val visObjects = ArrayList<Vis>()
        val grids = ArrayList<ProgressGrid>()
        val buttons = ArrayList<Button>()

        fun walk(o: GuiObject) {
            all.add(o)
            when (o) {
                is Slider -> sliders.add(o)
                is Text -> texts.add(o)
                is Vis -> visObjects.add(o)
                is ProgressGrid -> grids.add(o)
                is Button -> buttons.add(o)
                is Group -> o.children.forEach { walk(it) }
                else -> Unit
            }
        }
    }

    // the objects of the windows on screen that move with the player or the clock, from the last layout pass
    private var sliders: List<Slider> = emptyList()
    private var texts: List<Text> = emptyList()
    private var visObjects: List<Vis> = emptyList()
    private var progressGrids: List<ProgressGrid> = emptyList()
    private var buttons: List<Button> = emptyList()

    /**
     * Whether [o] shows: in a window on screen, it and every group around it visible, not faded
     * out, and with room to draw in.
     */
    internal fun shows(o: GuiObject): Boolean {
        val room = o.rect.width > 0 && o.rect.height > 0
        if (!room || !o.visible || o.effectiveAlpha <= 0) return false
        var top = o
        while (true) {
            val up = top.parent ?: break
            if (!up.visible) return false
            top = up
        }
        return top is Layout && windows.any { it === top }
    }

    /**
     * Whether something on the skin moves smoothly by itself now and wants a frame on every display
     * frame: a visualizer that shows while the music plays, and while it falls to rest after; while
     * the music plays or is paused, also a target slide or an animation playing and not paused.
     * Stopped, a slide or an animation steps when [nextDueAt] says. Tickers, timers, the time and
     * progress grids never want frames: they change at the moments [nextDueAt] gives.
     */
    val framesWanted: Boolean
        get() {
            val status = player.status
            for (v in visObjects) if (v.moving(status) && shows(v)) return true
            if (status == Player.Status.STOPPED) return false
            return moving.isNotEmpty() || animations.any { it.running && shows(it) }
        }

    /**
     * When, in [clock] milliseconds, something on the skin next changes by itself: a running timer
     * firing, an animation's next frame, a target slide (now), a temporary message going back, a
     * ticker's next step (while playing or paused, a songticker only while playing), a progress
     * grid's next pixel while playing, or a moving visualizer's next 25 ms step. Null when nothing
     * will. A time due already comes back as it is.
     */
    fun nextDueAt(): Long? {
        val now = now()
        if (moving.isNotEmpty()) return now
        val status = player.status
        var due = Long.MAX_VALUE
        for (timer in timers) if (timer.running) due = minOf(due, timer.nextAt)
        for (a in animations) if (a.running) due = minOf(due, a.nextAt)
        for (text in texts) if (shows(text)) text.nextDueAt(now, status)?.let { due = minOf(due, it) }
        for (v in visObjects) if (v.moving(status) && shows(v)) due = minOf(due, v.nextStepAt(now))
        if (status == Player.Status.PLAYING) {
            for (g in progressGrids) if (shows(g)) g.nextFillAt(now)?.let { due = minOf(due, it) }
        }
        return due.takeIf { it != Long.MAX_VALUE }
    }

    /** Script steps run since the last [tick] began, across every script. */
    val scriptSteps: Long get() = scripts.sumOf { it.stepsSinceRefill.toLong() }

    /** Timer events fired so far. */
    var timerFires = 0L
        private set

    /**
     * Advances timers, tickers and player-driven objects. Returns true when a redraw is due: when
     * something that shows changed, a visualizer that moves only once its 25 ms step has passed.
     */
    fun tick(): Boolean {
        val t = now()
        advanceClocks(t)
        val status = player.status
        announcePlayer(status)
        if (needsLayout) doLayout()
        for (o in sliders) o.sync()
        // a text out of sight still keeps its ticker and its message up to date
        for (o in texts) if (o.tick(t) && shows(o)) dirty = true
        for (o in visObjects) if (o.moving(status) && o.stepDue(t) && shows(o)) dirty = true
        for (o in progressGrids) if (o.filledChanged() && shows(o)) dirty = true
        for (o in buttons) if (o.stateChanged() && shows(o)) dirty = true
        if (needsLayout) doLayout()
        return dirty
    }

    // what is due at a tick, gathered first: what it runs may start, stop or end others
    private val dueAnimations = ArrayList<AnimatedLayer>()
    private val dueSlides = ArrayList<GuiObject>()
    private val dueTimers = ArrayList<Timer>()

    /** Refills every script's step budget, then moves animations and target slides and fires due timers, at [t]. */
    private fun advanceClocks(t: Long) {
        for (s in scripts) s.refill()
        dueAnimations.clear()
        for (a in animations) if (a.playing && t >= a.nextAt) dueAnimations.add(a)
        for (a in dueAnimations) a.advance()
        dueSlides.clear()
        for (o in moving) dueSlides.add(o)
        for (o in dueSlides) o.stepTarget(t)
        dueTimers.clear()
        for (timer in timers) if (timer.running && t >= timer.nextAt) dueTimers.add(timer)
        for (timer in dueTimers) {
            timer.nextAt = t + maxOf(1, timer.delay)
            timerFires++
            dispatch(timer, "ontimer", emptyList())
        }
    }

    /**
     * Tells the scripts what changed in the player since the last tick, as System events: its
     * [status], the title, the equalizer and the volume. Each of these, the shuffle, repeat and
     * crossfade switches, a changed playlist or current entry asks for a frame, as an equalizer
     * curve, a button or a display may show it.
     */
    private fun announcePlayer(status: Player.Status) {
        if (status != lastStatus) {
            when (status) {
                Player.Status.PLAYING -> {
                    dispatchSystem(if (lastStatus == Player.Status.PAUSED) "onresume" else "onplay")
                    // a start announces the song: Winamp fires onTitleChange when playback begins, and
                    // Bento's file info lays its lines out on that
                    if (lastStatus == Player.Status.STOPPED) dispatchSystem("ontitlechange", Value.Str(player.title))
                }

                Player.Status.PAUSED -> {
                    dispatchSystem("onpause")
                }

                Player.Status.STOPPED -> {
                    dispatchSystem("onstop")
                }
            }
            lastStatus = status
            dirty = true
        }
        if (player.title != lastTitle) {
            lastTitle = player.title
            dispatchSystem("ontitlechange", Value.Str(lastTitle))
            dirty = true
        }
        if (player.eqEnabled != lastEqOn) {
            lastEqOn = player.eqEnabled
            dispatchSystem("oneqchanged", Value.Int(if (lastEqOn) 1 else 0))
            dirty = true
        }
        if (player.eqPreamp != lastPreamp) {
            lastPreamp = player.eqPreamp
            dispatchSystem("oneqpreampchanged", Value.Int((lastPreamp - EQ_FLAT).coerceIn(-127, 127)))
            dirty = true
        }
        for (i in lastBands.indices) {
            val v = player.eqBands.getOrElse(i) { EQ_FLAT }
            if (v != lastBands[i]) {
                lastBands[i] = v
                // the band and its value on getEqBand's scale, -127..127
                dispatchSystem("oneqbandchanged", Value.Int(i), Value.Int((v - EQ_FLAT).coerceIn(-127, 127)))
                dirty = true
            }
        }
        if (player.volume != lastVolume) {
            lastVolume = player.volume
            dispatchSystem("onvolumechanged", Value.Int(lastVolume))
            dirty = true
        }
        // the buttons and config values that show shuffle, repeat and crossfade
        val switches = switches()
        if (switches != lastSwitches) {
            lastSwitches = switches
            dirty = true
        }
        // another list with the same rows shows the same (a player may build the list on every read)
        val tracks = player.tracks
        if (tracks !== lastTracks) {
            if (tracks != lastTracks) dirty = true
            lastTracks = tracks
        }
        if (player.trackIndex != lastTrackIndex) {
            lastTrackIndex = player.trackIndex
            dirty = true
        }
    }

    /** The main window's drawing. */
    fun draw(): List<DrawCmd> = mainLayout?.let { draw(it) } ?: emptyList()

    /** Every window's drawing with its place in the stack; draw each translated to its rectangle. */
    fun drawWindows(): List<Pair<Rect, List<DrawCmd>>> {
        if (needsLayout) doLayout()
        return windows.map { windowRect(it) to draw(it) }
    }

    fun draw(layout: Layout): List<DrawCmd> {
        if (needsLayout) doLayout()
        dirty = false
        val drawn = ArrayList<DrawCmd>()
        layout.draw(drawn)
        val out = ArrayList<DrawCmd>(drawn.size)
        for (cmd in drawn) {
            when (cmd) {
                is DrawCmd.Image -> {
                    fitToSheet(cmd)?.let {
                        it.gamma = gamma(it.bitmap.gammaGroup)
                        out.add(it)
                    }
                }

                is DrawCmd.WindowStart -> {
                    out.add(windowStart(layout, cmd))
                }

                is DrawCmd.BitmapText -> {
                    cmd.gamma = gamma(cmd.font.gammaGroup)
                    out.add(cmd)
                }

                else -> {
                    out.add(cmd)
                }
            }
        }
        return out
    }

    /** A window's shape as [windowStart] last gave it: the shape it was made from, and the command. */
    private class Shaped(
        val from: Region,
        val cmd: DrawCmd.WindowStart,
    )

    private val shaped = HashMap<Layout, Shaped>()

    /**
     * The start of [layout]'s shaped window with its pictures fitted to their sheets and tinted for
     * the color theme. A window keeps its shape until the skin changes (see [changeCount]), and a
     * new theme changes it, so the same shape gives back the same command and region from frame to
     * frame, which a renderer may keep what it made from.
     */
    private fun windowStart(
        layout: Layout,
        cmd: DrawCmd.WindowStart,
    ): DrawCmd.WindowStart {
        shaped[layout]?.takeIf { it.from === cmd.region }?.let { return it.cmd }
        val region = cmd.region.mapPics(::fitToSheet) ?: Region.Op(null, emptyList(), emptyList(), emptyList())
        region.pics().forEach { it.gamma = gamma(it.bitmap.gammaGroup) }
        val fitted = DrawCmd.WindowStart(region).also { it.ownedBy = cmd.ownedBy }
        shaped[layout] = Shaped(cmd.region, fitted)
        return fitted
    }

    /**
     * The part of [cmd] that lies on its sheet, with the destination cut the same way: Winamp draws
     * nothing for rows or columns a bitmap element asks for past the edge of its file (Winamp
     * Modern's display asks for 67 rows of a 63-row strip). Renderers then never see a source
     * outside the picture. Null when nothing is left.
     */
    private fun fitToSheet(cmd: DrawCmd.Image): DrawCmd.Image? {
        val b = cmd.bitmap
        if (b.fit == BitmapDef.FIT_UNKNOWN) fitPart(b)
        val part = b.fitted?.takeIf { b.fit == BitmapDef.FIT_CUT }
        return when {
            b.fit == BitmapDef.FIT_NONE -> null
            part == null -> cmd
            else -> cut(cmd, part)
        }
    }

    /** [cmd] drawing only [part], the share of its picture that lies on the sheet. */
    private fun cut(
        cmd: DrawCmd.Image,
        part: BitmapDef,
    ): DrawCmd.Image? {
        val b = cmd.bitmap
        val fw = part.w ?: b.askedW
        val fh = part.h ?: b.askedH
        // a stretched picture keeps its scale: the destination shrinks by the same share
        val dw = if (fw == b.askedW) cmd.dst.width else cmd.dst.width * fw / b.askedW
        val dh = if (fh == b.askedH) cmd.dst.height else cmd.dst.height * fh / b.askedH
        return if (dw <= 0 || dh <= 0) {
            null
        } else {
            DrawCmd
                .Image(part, Rect(cmd.dst.left, cmd.dst.top, dw, dh), cmd.alpha)
                .also {
                    it.ownedBy = cmd.ownedBy
                    it.smooth = cmd.smooth
                }
        }
    }

    /**
     * Works out once how much of the part [b] names lies on its sheet (see [BitmapDef.fit]); a sheet
     * whose size is not known yet leaves it to the next frame.
     */
    private fun fitPart(b: BitmapDef) {
        val (sheetW, sheetH) = host.imageSize(b.file) ?: return
        val sw = b.w ?: (sheetW - b.x)
        val sh = b.h ?: (sheetH - b.y)
        val fw = minOf(sw, sheetW - b.x)
        val fh = minOf(sh, sheetH - b.y)
        val empty = fw <= 0 || fh <= 0
        val outside = sw <= 0 || sh <= 0
        b.fit =
            when {
                empty || outside -> {
                    BitmapDef.FIT_NONE
                }

                fw == sw && fh == sh && b.w != null && b.h != null -> {
                    BitmapDef.FIT_WHOLE
                }

                else -> {
                    b.fitted = BitmapDef(b.id, b.file, b.x, b.y, fw, fh, b.gammaGroup)
                    b.askedW = sw
                    b.askedH = sh
                    BitmapDef.FIT_CUT
                }
            }
    }

    // ---- pointer -----------------------------------------------------------------------------

    /** The window under a point of the stack, or the nearest within [touchSlop]; see [StackArrange.windowAt]. */
    private fun windowAt(
        x: Int,
        y: Int,
    ): Layout? {
        if (needsLayout) doLayout()
        return StackArrange.windowAt(windows, ::windowRect, x to y, touchSlop)
    }

    /** The topmost object at a point of the stack. */
    fun hitTest(
        x: Int,
        y: Int,
    ): GuiObject? {
        val l = windowAt(x, y) ?: return null
        val r = windowRect(l)
        return l.hitTest(x - r.left, y - r.top)
    }

    /** Whether a touch on [o] does something: a control, or an object a script listens to. */
    private fun reacts(o: GuiObject): Boolean = isControl(o) || scripts.any { it.listensTo(o) }

    private fun isControl(o: GuiObject): Boolean = o is Button || o is Slider || o is ColorThemeList || o is PlaylistView

    /**
     * How far, in layout pixels, a finger may land beside an object and still mean it. The app sets
     * it from the screen: 24 dp, so even a tiny button answers across Android's 48 dp touch target.
     */
    var touchSlop = 6

    /**
     * Whether [o] can take a touch at all. [faded] also lets in one drawn fully transparent, which
     * a mouse still clicks: a seek bar's twin slider at alpha 1 over the one that shows.
     */
    private fun touchable(
        o: GuiObject,
        faded: Boolean = false,
    ): Boolean =
        o.visible && o.enabled && (faded || o.effectiveAlpha > 0) && o.rect.width > 0 && o.rect.height > 0 &&
            generateSequence(o.parent) { it.parent }.all { it.visible }

    /**
     * Whether a mouse could click [o] in [window] at one of a few of its points: the one nearest the
     * finger at [x], [y], its center and its corners. An object partly under a label counts while
     * one of those points is uncovered; one wholly behind another does not.
     */
    private fun clickable(
        o: GuiObject,
        window: Layout,
        x: Int,
        y: Int,
    ): Boolean {
        val r = o.rect
        val right = r.right - 1
        val bottom = r.bottom - 1
        val points =
            listOf(
                x.coerceIn(r.left, right) to y.coerceIn(r.top, bottom),
                (r.left + right) / 2 to (r.top + bottom) / 2,
                r.left to r.top,
                right to r.top,
                r.left to bottom,
                right to bottom,
            )
        return points.any { (px, py) ->
            generateSequence(window.hitTest(px, py)) { it.parent }.any { it === o }
        }
    }

    /** Squared distance from a point to [o]'s rectangle; 0 inside. */
    private fun distance2(
        o: GuiObject,
        x: Int,
        y: Int,
    ): Int {
        val dx = maxOf(o.rect.left - x, 0, x - (o.rect.right - 1))
        val dy = maxOf(o.rect.top - y, 0, y - (o.rect.bottom - 1))
        return dx * dx + dy * dy
    }

    /**
     * What a finger at a point means. A finger is wider than a mouse pointer, so in order: a control
     * right under it; the nearest control within [touchSlop]; an object a script listens to right
     * under it; the nearest such object within [touchSlop]. Controls come first so a background a
     * script watches does not take a tap meant for the button beside it. The topmost wins a tie.
     * A nearby control counts only if a mouse could click it at one of a few of its points (see
     * [clickable]), so one hidden behind another, like a closed drawer's sliders behind the main
     * window, takes no touch. A nearby script object counts if the script listens to it for
     * presses or moves, even under another object: a vis trigger often lies under the vis.
     */
    fun touchTarget(
        sx: Int,
        sy: Int,
    ): GuiObject? {
        val window = windowAt(sx, sy) ?: return null
        val origin = windowRect(window)
        val x = sx - origin.left
        val y = sy - origin.top
        val hit = window.hitTest(x, y)
        if (hit != null && isControl(hit)) return hit
        val slop2 = touchSlop * touchSlop

        // the cheap tests first: a script's bindings are read only for objects within reach
        fun nearest(
            shown: Boolean,
            pick: (GuiObject) -> Boolean,
        ): GuiObject? =
            objects
                .asReversed()
                .asSequence()
                .filter { it.layoutObject === window && touchable(it, faded = shown) }
                .map { it to distance2(it, x, y) }
                .filter { (o, d) -> d <= slop2 && pick(o) && (!shown || clickable(o, window, x, y)) }
                .minByOrNull { it.second }
                ?.first
        return nearest(shown = true, ::isControl)
            ?: hit?.takeIf { reacts(it) }
            ?: nearest(shown = false) { o -> !isControl(o) && scripts.any { it.listensTo(o, POINTER_EVENTS) } }
            ?: hit
    }

    /** Where the captured object's window sits, to turn stack points into its own. */
    private var capturedOrigin = Rect(0, 0, 0, 0)

    /** A press its object has not heard yet: the object, and the point of the stack the finger went down on. */
    private class DeferredPress(
        val target: GuiObject,
        val sx: Int,
        val sy: Int,
    )

    /**
     * The press [pointerDown] keeps from its object until the touch shows what it is. A press on an
     * object with a skin menu (see [menuTarget]), other than a slider, reaches it only once the
     * touch can no longer be a long press: the release sends the press and then the release, a move
     * beyond [touchSlop] sends the press and then the move, and [pointerCancel] or [rightClick] drop
     * it. A long press then opens the menu without what the object does on a press, such as a
     * visualizer switching its mode. A button shows pressed meanwhile; its scripts hear nothing.
     */
    private var deferred: DeferredPress? = null

    /**
     * Pointer events take points of the stack (see [windowRect]); objects hear points of their
     * window. [seq] names this touch for [menuTarget], [moveTarget] and [rightClick].
     */
    fun pointerDown(
        sx: Int,
        sy: Int,
        seq: Int = 0,
    ) = gesture(fresh = true) {
        aimMenu(sx, sy, seq)
        moveSeq = null
        pointerDownAt(sx, sy)
        // a press that opened a menu has been let go of, and moves nothing; a press held back from
        // an object with a skin menu still moves the window when that object is a move area
        if ((captured ?: deferred?.target)?.let(::movesWindow) == true) moveSeq = seq
    }

    private fun pointerDownAt(
        sx: Int,
        sy: Int,
    ) {
        dropDeferred()
        if (needsLayout) doLayout()
        val target = touchTarget(sx, sy) ?: return
        // the touched window takes the focus, as a press of either mouse button gives it
        target.layoutObject?.let(::takeFocus)
        if (target === menuAim?.target && target !is Slider) {
            deferred = DeferredPress(target, sx, sy)
            showPressed(target, true)
            return
        }
        press(target, sx, sy)
    }

    /** Shows a held press's button pressed, or no longer; its scripts hear nothing of it. */
    private fun showPressed(
        target: GuiObject,
        pressed: Boolean,
    ) {
        if (target !is Button) return
        target.pressed = pressed
        repaint()
    }

    /** The object hears the button go down at a point of the stack, and the finger come in. */
    private fun press(
        target: GuiObject,
        sx: Int,
        sy: Int,
    ) {
        capturedOrigin = target.layoutObject?.let { windowRect(it) } ?: Rect(0, 0, 0, 0)
        val x = sx - capturedOrigin.left
        val y = sy - capturedOrigin.top
        lastPointer = x to y
        captured = target
        target.pointerDown(x, y)
        // a menu the press opened has let go of it already
        if (captured === target) {
            entered = true
            dispatch(target, "onenterarea", emptyList())
        }
    }

    fun pointerMove(
        sx: Int,
        sy: Int,
    ) = gesture { pointerMoveAt(sx, sy) }

    /** Sends the deferred press where the finger went down, unless its object has gone from the skin. */
    private fun sendDeferred() {
        val d = deferred ?: return
        dropDeferred()
        if (needsLayout) doLayout()
        if (d.target in objects) press(d.target, d.sx, d.sy)
    }

    /** Lets the deferred press go unsent: its object hears nothing of it. */
    private fun dropDeferred() {
        val d = deferred ?: return
        deferred = null
        showPressed(d.target, false)
    }

    private fun pointerMoveAt(
        sx: Int,
        sy: Int,
    ) {
        deferred?.let { d ->
            val dx = sx - d.sx
            val dy = sy - d.sy
            // still a tap or a long press
            if (dx * dx + dy * dy <= touchSlop * touchSlop) return
            sendDeferred()
        }
        val x = sx - capturedOrigin.left
        val y = sy - capturedOrigin.top
        lastPointer = x to y
        captured?.pointerMove(x, y)
    }

    fun pointerUp(
        sx: Int,
        sy: Int,
    ) = gesture { pointerUpAt(sx, sy) }

    private fun pointerUpAt(
        sx: Int,
        sy: Int,
    ) {
        sendDeferred()
        val x = sx - capturedOrigin.left
        val y = sy - capturedOrigin.top
        lastPointer = x to y
        val target = captured ?: return
        captured = null
        // a finger that drifts a little still counts as on the control
        val r = target.rect
        val inside = x >= r.left - touchSlop && x < r.right + touchSlop && y >= r.top - touchSlop && y < r.bottom + touchSlop
        target.pointerUp(x, y, inside)
        leaveArea(target)
    }

    /** A second tap of a double tap, at a point of the stack; [seq] names it as [pointerDown]'s does. */
    fun doubleTap(
        sx: Int,
        sy: Int,
        seq: Int = 0,
    ) = gesture(fresh = true) {
        aimMenu(sx, sy, seq)
        moveSeq = null
        doubleTapAt(sx, sy)
    }

    private fun doubleTapAt(
        sx: Int,
        sy: Int,
    ) {
        dropDeferred()
        val target = hitTest(sx, sy) ?: return
        // objects hear points of their window, as with every other pointer event
        val origin = target.layoutObject?.let { windowRect(it) } ?: Rect(0, 0, 0, 0)
        target.doubleTap(sx - origin.left, sy - origin.top)
    }

    /** The object with a skin menu a touch landed on: the touch's seq, and the point in the object's window. */
    private class MenuAim(
        val seq: Int,
        val target: GuiObject,
        val x: Int,
        val y: Int,
    )

    private var menuAim: MenuAim? = null

    /**
     * The seq of the last [pointerDown] or [doubleTap] when it landed on an object with a skin menu
     * (see [hasSkinMenu]), else null: [rightClick] with that seq opens the menu.
     */
    val menuTarget: Int? get() = menuAim?.seq

    private var moveSeq: Int? = null

    /**
     * The seq of the last [pointerDown] when the object it pressed moves the window (see
     * [movesWindow]), else null; a [doubleTap] clears it. The host moves the skin when such a press
     * turns into a drag.
     */
    val moveTarget: Int? get() = moveSeq

    /**
     * Whether a drag that starts on [o] moves the window, as Winamp moves one by its move areas:
     * what [o] writes in `move`, else what the nearest group around it writes. A wasabi title bar's
     * mousetrap moves it unless it says otherwise; a control never does, as a drag works the control.
     */
    internal fun movesWindow(o: GuiObject): Boolean {
        if (isControl(o)) return false
        o.move?.let { return it }
        if (o.id == TITLE_BAR_TRAP && o.parent?.id == TITLE_BAR) return true
        return generateSequence(o.parent) { it.parent }.firstNotNullOfOrNull { it.move } ?: false
    }

    /** Remembers the object with a skin menu under a touch at a point of the stack, or none. */
    private fun aimMenu(
        sx: Int,
        sy: Int,
        seq: Int,
    ) {
        menuAim = null
        if (needsLayout) doLayout()
        val window = windowAt(sx, sy) ?: return
        val origin = windowRect(window)
        val target = menuObjectAt(window, sx - origin.left, sy - origin.top) ?: return
        menuAim = MenuAim(seq, target, sx - origin.left, sy - origin.top)
    }

    /**
     * The object a right click at a point of [window] means, among objects with a skin menu only:
     * the topmost under the finger, else the nearest within [touchSlop] (the topmost on a tie). A
     * ghost takes no clicks; one drawn fully transparent does, as a mouse clicks it.
     */
    private fun menuObjectAt(
        window: Layout,
        x: Int,
        y: Int,
    ): GuiObject? {
        val slop2 = touchSlop * touchSlop
        // the cheap tests first: a script's bytecode is read only for objects within reach; those
        // under the finger are at 0, and the first of the nearest is the topmost
        return objects
            .asReversed()
            .asSequence()
            .filter { it.layoutObject === window && !it.ghost && touchable(it, faded = true) }
            .map { it to distance2(it, x, y) }
            .filter { (o, d) -> d <= slop2 && skinMenus.getOrPut(o) { hasSkinMenu(o) } }
            .minByOrNull { it.second }
            ?.first
    }

    /**
     * What [hasSkinMenu] answered for each object since the last layout pass. The scripts' bindings
     * are read once per object for as long as the windows keep their objects; a script that binds
     * a variable to another object is heard at the next layout pass.
     */
    private val skinMenus = HashMap<GuiObject, Boolean>()

    /** How many times [hasSkinMenu] read the scripts' bindings, for tests. */
    internal var skinMenuReads = 0L
        private set

    /**
     * Whether a right click on [o] can open a menu of the skin's, read from the scripts' bytecode
     * without running them (see [Script.rightClickMenu]). It counts the handlers a right click fires:
     * on [o] and, where [o] is the child a xui group exposes, on that group, in every script. It
     * follows one level further: an event such a handler fires by calling it (Bento's onRightClick
     * calls onLeftClick), or by calling a Button's leftClick(), counts with its handlers on the same
     * objects. A menu behind a condition counts; one a timer or another object's event opens does
     * not. Each script's bytecode is read up to a limit (see [Script.walkBudget]).
     */
    internal fun hasSkinMenu(o: GuiObject): Boolean {
        skinMenuReads++
        val chain = dispatchChain(o)
        val events = Script.rightClickEvents(o is Button)
        if (chain.any { c -> scripts.any { it.reachesMenu(c, events) } }) return true
        val called = chain.flatMapTo(HashSet()) { c -> scripts.flatMap { it.eventsCalled(c, events) } }
        return called.isNotEmpty() && chain.any { c -> scripts.any { it.reachesMenu(c, called) } }
    }

    /** The objects an event on [o] fires on: [o], then each group that exposes the one before as its xui child. */
    private fun dispatchChain(o: GuiObject): List<GuiObject> {
        val chain = arrayListOf(o)
        var at = o
        while (true) {
            val p = at.parent
            if (p !is Group || p.embedXui != at.id || p in chain) break
            chain += p
            at = p
        }
        return chain
    }

    /**
     * A right click on the object the touch [seq] landed on, at the point it landed (see
     * [menuTarget]), as a separate touch: onRightButtonDown and onRightButtonUp, and on a Button
     * then onRightClick. A menu a handler pops shows, even after the host ended the press with
     * [pointerCancel]. Nothing happens for an older touch's seq, or when the object is no longer on
     * screen (its window closed or its layout switched away).
     */
    fun rightClick(seq: Int) =
        gesture(fresh = true) {
            val aim = menuAim?.takeIf { it.seq == seq } ?: return@gesture
            // the touch was a long press: a deferred press goes nowhere
            dropDeferred()
            if (needsLayout) doLayout()
            if (aim.target !in objects) return@gesture
            // a press still held lets go first, as a right click takes the mouse
            if (captured != null) endPress()
            lastPointer = aim.x to aim.y
            aim.target.rightClick(aim.x, aim.y)
            if (aim.target is Button) aim.target.dispatch("onrightclick")
        }

    /** Pointer calls running now; a skin may pop a menu only from one, as a reply to the listener's touch. */
    private var gestureDepth = 0

    /** Menus shown in this touch, counted from the last `gesture(fresh = true)`. */
    private var menusThisGesture = 0

    /** A menu in this touch closed without the listener: the touch shows no more. */
    private var menusEnded = false

    /**
     * A menu ended this touch's press: its moves, release and long press go nowhere, and the
     * control that was pressed gives no click or final position for it.
     */
    internal var pressEnded = false
        private set

    /** The pressed object was told the finger came in, and is owed `onLeaveArea`. */
    private var entered = false

    /** Why menus were refused, each logged once: a timer may try every tick. */
    private val menuRefusalsLogged = HashSet<String>()

    /**
     * Runs [block] as a touch the listener made, from which a script may pop a menu. The pointer
     * calls run in it; a host that opens the skin's menu itself wraps that call in it too. [fresh]
     * starts a new touch, whose menu count starts over.
     */
    fun <T> gesture(
        fresh: Boolean = false,
        block: () -> T,
    ): T {
        if (fresh) {
            menusThisGesture = 0
            menusEnded = false
            pressEnded = false
        }
        gestureDepth++
        try {
            return block()
        } finally {
            gestureDepth--
        }
    }

    /**
     * The listener's pick from a menu a script popped, by command id, or 0. A menu is shown only in
     * reply to a touch (a timer cannot pop one), at most [MAX_MENUS_PER_GESTURE] times per touch,
     * not after one closed without the listener, and not from what a press ending without a
     * release tells its scripts (see [cancelPress]). It first ends the press under the finger, as
     * Windows hands the mouse to the menu: the control is let go without a release, and gives no
     * click or final position for this touch, even when its release handler opened the menu.
     */
    fun menuChoice(menu: SkinMenu): Int {
        val refusal =
            when {
                gestureDepth == 0 -> "outside a touch"
                menusEnded -> "after one closed without the listener"
                cancelling -> "while a press ends without a release"
                menusThisGesture >= MAX_MENUS_PER_GESTURE -> "more than $MAX_MENUS_PER_GESTURE in one touch"
                else -> null
            }
        if (refusal != null) {
            if (menuRefusalsLogged.add(refusal)) host.log("popup menu $refusal: not shown")
            return 0
        }
        menusThisGesture++
        endPress()
        val picked = host.chooseFromMenu(menu)
        if (picked == null) menusEnded = true
        return picked ?: 0
    }

    /**
     * Ends the press under the finger without a release: a button no longer shows pressed and gives
     * no click, a slider stops dragging without a final position (its scripts hear the button go
     * up, and a menu they pop then is not shown), and the object hears `onLeaveArea` to pair the
     * `onEnterArea` of [pointerDown]. The rest of this touch goes nowhere.
     * For a host that takes the touch, such as a long press that opens the app's menu.
     */
    fun pointerCancel() = gesture { endPress() }

    /** Lets go of the pressed object without a release, and tells it the finger left. */
    private fun endPress() {
        pressEnded = true
        dropDeferred()
        val held = captured ?: return
        captured = null
        cancelPress(held)
        leaveArea(held)
    }

    /** A press is ending without a release: [menuChoice] shows nothing its scripts pop meanwhile. */
    private var cancelling = false

    /** Lets go of [held] without a release (see [GuiObject.pointerCancel]); its scripts pop no menu meanwhile. */
    private fun cancelPress(held: GuiObject) {
        cancelling = true
        try {
            held.pointerCancel()
        } finally {
            cancelling = false
        }
    }

    /** `onLeaveArea` for [o], once per press that sent it `onEnterArea`. */
    private fun leaveArea(o: GuiObject) {
        if (!entered) return
        entered = false
        dispatch(o, "onleavearea", emptyList())
    }

    // ---- actions -----------------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // one arm per Winamp action name
    fun action(
        source: GuiObject,
        action: String,
        param: String?,
        target: String?,
    ) {
        val a = action.uppercase()
        host.log("action $a ${param ?: ""} from ${source.id}")
        when (a) {
            "PLAY" -> {
                player.play()
            }

            "PAUSE" -> {
                player.pause()
            }

            "STOP" -> {
                player.stop()
            }

            "PREV" -> {
                player.previous()
            }

            "NEXT" -> {
                player.next()
            }

            "EJECT" -> {
                player.eject()
            }

            "VOLUME_UP" -> {
                player.volume = (player.volume + 10).coerceAtMost(255)
            }

            "VOLUME_DOWN" -> {
                player.volume = (player.volume - 10).coerceAtLeast(0)
            }

            "TOGGLE" -> {
                val id = param?.lowercase()?.removePrefix("guid:") ?: return
                val kind = windowKind(id)
                val answer = onHostAction?.takeIf { kind in HOST_WINDOWS || toggleTarget(id) == null }
                if (answer != null) {
                    answer(a, kind)
                    // the app shows it: the skin's frame for it (an empty library) stays shut
                    if (kind in HOST_WINDOWS) {
                        invalidate()
                        return
                    }
                }
                toggleWindow(id, param)
                invalidate()
            }

            "SWITCH" -> {
                param?.let { source.layoutObject?.container?.switchTo(it) }
            }

            // a docked window's close button puts it away, unless canclose="0"; the main window stays
            // (the app has no desktop to close it to)
            "CLOSE" -> {
                val c = source.layoutObject?.container
                if (c === mainContainer) {
                    onHostAction?.invoke(a, param)
                } else if (c != null && c.canClose) {
                    closeWindow(c)
                }
            }

            "MINIMIZE", "SYSMENU", "CONTROLMENU", "MENU", "TRACKINFO" -> {
                onHostAction?.invoke(a, param)
            }

            "EQ_TOGGLE" -> {
                player.eqEnabled = !player.eqEnabled
            }

            "EQ_AUTO" -> {
                configFlags["eq_auto"] = !(configFlags["eq_auto"] ?: false)
            }

            "VIS_NEXT", "VIS_PREV", "VIS_MENU", "VIS_FS" -> {
                onHostAction?.invoke(a, param)
            }

            "TEXT_LARGER", "TEXT_SMALLER" -> {
                Unit
            }

            "COLORTHEMES_SWITCH", "COLORTHEMES_PREVIOUS", "COLORTHEMES_NEXT" -> {
                val list =
                    target?.let {
                        source.parent?.findObject(it) ?: windows.firstNotNullOfOrNull { l ->
                            l.findObject(it)
                        }
                    } as? ColorThemeList
                        ?: objects.filterIsInstance<ColorThemeList>().firstOrNull()
                        ?: return
                when (a) {
                    "COLORTHEMES_SWITCH" -> list.switchToSelected()
                    "COLORTHEMES_PREVIOUS" -> list.step(-1)
                    else -> list.step(1)
                }
                invalidate()
            }

            else -> {
                val t = target?.let { source.parent?.findObject(it) ?: windows.firstNotNullOfOrNull { l -> l.findObject(it) } }
                if (t !=
                    null
                ) {
                    dispatch(
                        t,
                        "onaction",
                        listOf(
                            Value.Str(action),
                            Value.Str(param ?: ""),
                            Value.Int(0),
                            Value.Int(0),
                            Value.Int(0),
                            Value.Int(0),
                            Value.Obj(source),
                        ),
                    )
                }
            }
        }
        invalidate()
    }

    /**
     * A TOGGLE action: opens or closes the window [id] names (see [toggleTarget]), or flips a flag
     * of that name when there is no such window. [param] is the action's parameter as written.
     */
    private fun toggleWindow(
        id: String,
        param: String,
    ) {
        val c = toggleTarget(id)
        if (c != null) {
            if (c in opened) {
                closeWindow(c)
            } else {
                // a component opened by its GUID takes the focus (Winamp Modern's playlist
                // editor, autofocus); a container shown for the first time does not
                // (Winamp3's EQ), one shown again does
                val takesFocus = param.contains("guid:", ignoreCase = true) || c in shownBefore
                openWindow(c)
                if (takesFocus) focused = c.current
            }
        } else {
            configFlags["window:$id"] = !(configFlags["window:$id"] ?: false)
        }
    }

    /** Whether the window or switch a button's action names is on, for its active image. */
    fun actionState(
        action: String?,
        param: String?,
    ): Boolean =
        when (action?.uppercase()) {
            "TOGGLE" -> {
                val id = param?.lowercase()?.removePrefix("guid:") ?: return false
                host.windowOpen(windowKind(id)) ?: toggleTarget(id)?.let { isOpen(it) } ?: (configFlags["window:$id"] ?: false)
            }

            "EQ_TOGGLE" -> {
                player.eqEnabled
            }

            "EQ_AUTO" -> {
                configFlags["eq_auto"] ?: false
            }

            else -> {
                false
            }
        }

    /** Whether [actionState] reads the state of [action]: a window, EQ on, EQ auto. */
    internal fun hasActionState(action: String?): Boolean = action?.uppercase() in STATE_ACTIONS

    /** The short name of a window a TOGGLE names by short name or component GUID. */
    private fun windowKind(id: String): String {
        val bare = id.trim('{', '}').lowercase()
        // a GUID or an alias ("library") by its first alias ("ml")
        val guid = TOGGLE_GUIDS[bare] ?: bare
        return TOGGLE_GUIDS.entries.firstOrNull { it.value == guid }?.key ?: bare
    }

    /** The window a TOGGLE names: a container id, a GUID alias such as "pl", or a component GUID. */
    private fun toggleTarget(id: String): Container? {
        val guid = TOGGLE_GUIDS[id] ?: id.trim('{', '}')
        return container(id)
            ?: containers.firstOrNull {
                it.attrs["component"]
                    ?.lowercase()
                    ?.removePrefix("guid:")
                    ?.trim('{', '}') == guid
            }
    }

    private val configValues = HashMap<String, Int>()
    private val configItems = HashMap<String, ConfigItemObj>()

    /** A `Config` item by name or GUID; [guid] given makes it when it does not exist yet. */
    fun configItem(
        key: String,
        guid: String?,
    ): ConfigItemObj? {
        val k = key.lowercase()
        return configItems[k]
            ?: configItems.values.firstOrNull { it.guid?.lowercase() == k }
            ?: guid?.let { g ->
                ConfigItemObj(this, key, g).also {
                    configItems[k] = it
                    configItems[g.lowercase()] = it
                }
            }
    }

    /** A numeric config attribute; repeat is 0 off, 1 the playlist, -1 the song. */
    fun configValue(name: String): Int =
        when (name.lowercase()) {
            "repeat" -> if (player.repeat) 1 else 0
            else -> scriptAttribute(name)?.data?.toIntOrNull() ?: configValues[name.lowercase()] ?: if (configFlag(name)) 1 else 0
        }

    /**
     * A config attribute a script made (Bento's "Show Playlist Tab"): a cfgattrib names it, and the
     * button and the script then share one value, so a tap is heard as onDataChanged and a script's
     * setData shows on the button (Wasabi SDK guiobjwnd, cfgitemi).
     */
    private fun scriptAttribute(name: String): ConfigAttributeObj? {
        val k = name.lowercase()
        return configItems.values.firstNotNullOfOrNull { it.attributes[k] }
    }

    fun setConfigValue(
        name: String,
        value: Int,
    ) {
        when (name.lowercase()) {
            "repeat" -> {
                player.repeat = value != 0
            }

            else -> {
                scriptAttribute(name)?.let { a ->
                    a.makiCall("setdata", listOf(Value.Str(value.toString())))
                    return
                }
                configValues[name.lowercase()] = value
                configFlags[name.lowercase()] = value != 0
            }
        }
    }

    fun configFlag(name: String): Boolean =
        when (name.lowercase()) {
            "shuffle" -> player.shuffle
            "repeat" -> player.repeat
            "enable crossfading", "crossfade" -> player.crossfade
            "enable equalizer", "eq" -> player.eqEnabled
            else -> scriptAttribute(name)?.let { it.data == "1" } ?: configFlags[name.lowercase()] ?: false
        }

    fun setConfigFlag(
        name: String,
        on: Boolean,
    ) {
        when (name.lowercase()) {
            "shuffle" -> {
                player.shuffle = on
            }

            "repeat" -> {
                player.repeat = on
            }

            "enable crossfading", "crossfade" -> {
                player.crossfade = on
            }

            "enable equalizer", "eq" -> {
                player.eqEnabled = on
            }

            else -> {
                scriptAttribute(name)?.makiCall("setdata", listOf(Value.Str(if (on) "1" else "0")))
                    ?: run { configFlags[name.lowercase()] = on }
            }
        }
    }

    /**
     * Where the player says a slider should sit, or null for one the player does not drive. It
     * rounds, as [sliderMoved] does, so a thumb let go stays where it was let go.
     */
    fun sliderTarget(s: Slider): Int? =
        when (s.action) {
            "seek" -> {
                if (player.lengthMs >
                    0
                ) {
                    s.low + scaled(player.positionMs, player.lengthMs, s.high - s.low)
                } else {
                    s.low
                }
            }

            "volume" -> {
                s.low + scaled(player.volume, 255, s.high - s.low)
            }

            "eq_band" -> {
                eqValue(s.param)?.let { s.low + scaled(it, 255, s.high - s.low) }
            }

            "eq_preamp" -> {
                s.low + scaled(player.eqPreamp, 255, s.high - s.low)
            }

            // balance sits in the middle; the player has no pan yet
            "pan" -> {
                (s.low + s.high) / 2
            }

            else -> {
                null
            }
        }

    /** An EQ slider's value: `param` is a band counted from 1, or "preamp". */
    private fun eqValue(param: String?): Int? =
        when {
            param.equals("preamp", ignoreCase = true) -> player.eqPreamp
            else -> param?.toIntOrNull()?.let { player.eqBands.getOrNull(it - 1) }
        }

    /** [value] out of [whole], as a share of [range], rounded to the nearest. */
    private fun scaled(
        value: Int,
        whole: Int,
        range: Int,
    ): Int = Math.round(value.toDouble() * range / whole).toInt()

    /** Drives the player from a slider the listener moved; the values round to the nearest. */
    fun sliderMoved(
        s: Slider,
        final: Boolean,
    ) {
        val frac = (s.position - s.low).toDouble() / maxOf(1, s.high - s.low)
        val level = Math.round(frac * 255).toInt()
        when (s.action) {
            "seek" -> {
                if (final) {
                    player.seekTo(Math.round(frac * player.lengthMs).toInt())
                    dispatchSystem("onseek", Value.Int(player.positionMs))
                }
            }

            "volume" -> {
                player.volume = level
            }

            "eq_band" -> {
                when {
                    s.param.equals("preamp", ignoreCase = true) -> player.eqPreamp = level
                    else -> s.param?.toIntOrNull()?.let { player.setEqBand(it - 1, level) }
                }
            }

            "eq_preamp" -> {
                player.eqPreamp = level
            }
        }
    }

    private companion object {
        /** Everything a groupdef holds, and its attributes that are no params of the group. */
        val ALL_CONTENT = setOf("params", "xui", "scripts")
        val DEF_ATTRS = setOf("id", "xuitag", "inherit_group", "inherit_content", "inherit_params", "behavior", "contentrect")
        const val MAX_LAYOUT_PASSES = 4
        const val MAX_EVENT_DEPTH = 48
        const val MAX_INCLUDE_DEPTH = 32
        const val MAX_XML_DEPTH = 256
        const val MAX_NODES = 200_000
        const val MAX_SETTINGS = 4_096

        /** The events of a press or a move; an object a script watches only for others takes no touch beside it. */
        val POINTER_EVENTS =
            setOf(
                "onleftbuttondown",
                "onleftbuttonup",
                "onrightbuttondown",
                "onrightbuttonup",
                "onleftbuttondblclk",
                "onrightbuttondblclk",
                "onmousemove",
                "onleftclick",
                "onrightclick",
            )

        /** Menus a script may pop in reply to one touch; more answer 0 without showing. */
        const val MAX_MENUS_PER_GESTURE = 3

        /** The most pixels [opaqueRight] reads of one picture; a larger one counts as showing to its right edge. */
        const val MAX_OPAQUE_SCAN = 512 * 512

        const val MAX_SETTING_LENGTH = 65_536
        const val MAX_OBJECTS = 100_000
        const val MAX_CREATED = 20_000
        val FILE_NODES = setOf("bitmap", "bitmapfont", "truetypefont", "script")

        /** The id of a wasabi title bar's group, and of the layer over the whole bar that takes its drags. */
        const val TITLE_BAR = "wasabi.titlebar"
        const val TITLE_BAR_TRAP = "mousetrap"

        const val MAX_ALIAS_DEPTH = 8
        const val EQ_FLAT = 128
        const val PLAYLIST_GUID = "45f3f7c1-a6f3-4ee6-a15e-125e92fc3f8d"
        const val LIBRARY_GUID = "6b0edf80-c9a5-11d3-9f26-00c04f39ffc6"

        /** Winamp's windows that show a list: the playlist editor and the media library. */
        val LIST_GUIDS = listOf(PLAYLIST_GUID, LIBRARY_GUID)

        /** The tags that embed one of Winamp's built-in windows in a skin window. */
        val HOLDER_TAGS = setOf("component", "windowholder")

        /** The windows the app shows when a host answers [onHostAction], by [windowKind]. */
        val HOST_WINDOWS = setOf("ml", "avs", "video")

        /** The actions whose state [actionState] reads. */
        val STATE_ACTIONS = setOf("TOGGLE", "EQ_TOGGLE", "EQ_AUTO")

        /** Winamp's GUID aliases, and the short names the host knows windows by. */
        val TOGGLE_GUIDS =
            mapOf(
                "pl" to PLAYLIST_GUID,
                "playlist" to PLAYLIST_GUID,
                "ml" to LIBRARY_GUID,
                "musiclibrary" to LIBRARY_GUID,
                "library" to LIBRARY_GUID,
                "avs" to "0000000a-000c-0010-ff7b-01014263450c",
                "video" to "f0816d7b-fffc-4343-80f2-e8199aa15cc3",
            )
        val GUI_ATTRS =
            setOf(
                "id",
                "x",
                "y",
                "w",
                "h",
                "relatx",
                "relaty",
                "relatw",
                "relath",
                "visible",
                "alpha",
                "ghost",
                "sysregion",
                "tooltip",
                "fitparent",
                "background",
                "drawbackground",
                "instance_id",
                "instanceid",
                "name",
                "move",
            )
        val KNOWN_CLASSES =
            mapOf(
                "grid" to "GuiObject",
                "progressgrid" to "GuiObject",
                "rect" to "GuiObject",
                "albumart" to "AlbumArtLayer",
                "layoutstatus" to "LayoutStatus",
                "menu" to "Menu",
                "browser" to "Browser",
                "edit" to "Edit",
                "checkbox" to "CheckBox",
                "dropdownlist" to "DropDownList",
                "guilist" to "GuiList",
                "guitree" to "GuiTree",
                "tabsheet" to "TabSheet",
                "wasabi:tabsheet" to "TabSheet",
                "playlisteditor" to "GuiObject",
                "wasabi:titlebar" to "GuiObject",
                "mouseredir" to "MouseRedir",
                "images" to "GuiObject",
            )
    }
}
