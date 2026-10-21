// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

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
@Suppress("TooManyFunctions", "LargeClass")
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
        map[k] = value
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

    var viewportW = 1080
    var viewportH = 1920
    var lastPointer = 0 to 0
    var popupMenu: List<String>? = null
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
            var key = name
            var hops = 0
            while (hops++ < 8) {
                val def = bitmaps[key]
                if (def != null) {
                    if (key !in baseBitmaps) return def
                    if (fromBase == null) fromBase = def
                    break
                }
                key = aliases[key] ?: break
            }
        }
        fromBase?.let {
            baseUsage.add("bitmap:${it.id}")
            return it
        }
        // a plain file name used as an image
        if (files.exists(id)) return bitmaps.getOrPut(id.lowercase()) { BitmapDef(id.lowercase(), id, 0, 0, null, null) }
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
        currentGammaSet = match
        invalidate()
    }

    fun gamma(group: String?): Gamma? {
        if (group == null) return null
        return currentGammaSet?.let { gammaSets[it] }?.get(group.lowercase())?.takeUnless { it.isIdentity }
    }

    fun color(
        id: String,
        depth: Int = 0,
    ): Rgb? {
        val names = namesFor(id).filter { it in colors || it in colorAliases }
        val key = names.firstOrNull { it !in baseColors } ?: names.firstOrNull() ?: return null
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
        val names = namesFor(id).filter { it in colors || it in colorAliases }
        val key = names.firstOrNull { it !in baseColors } ?: names.firstOrNull() ?: return null
        return colors[key] ?: colorAliases[key]?.takeIf { depth < MAX_ALIAS_DEPTH }?.let { plainColor(it, depth + 1) }
    }

    fun container(id: String): Container? = containers.firstOrNull { it.id.equals(id, ignoreCase = true) }

    val mainContainer: Container? get() = container("main") ?: containers.firstOrNull()
    val mainLayout: Layout? get() = mainContainer?.current

    /**
     * Windows a skin button opened (playlist, color themes, ...), in the order they opened. A phone
     * shows them stacked under the main window, as Winamp's docked windows sit: each as wide as the
     * main window, the last one filling the height that is left (see [viewSize]).
     */
    private val opened = ArrayList<Container>()

    /** The windows on screen, top to bottom: the main one first. */
    val windows: List<Layout> get() = listOfNotNull(mainLayout) + opened.mapNotNull { it.current }

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

    /** Stacks the windows: the main one at its size, the others at its width under it. */
    private fun arrange() {
        placed.clear()
        val main = mainLayout ?: return
        val (mw, mh) = main.size
        placed[main] = Rect(0, 0, mw, mh)
        // the next window docks under what is visible of the main one, so it follows the drawer
        main.layoutRoot()
        var y = main.contentBottom()
        val others = windows.drop(1)
        val view = viewSize
        others.forEachIndexed { i, l ->
            // without a screen size (the reference tests) every window keeps its own size
            if (view != null) {
                // as wide as what shows of the main window (MMD3 keeps room on its right for a panel)
                l.w = main.contentRight()
                // the skin's own height (or its minimum), unless it is the last, room is left and the
                // window can be made taller
                l.h = l.attrs["h"]?.toIntLenient()
                val natural = l.size.second
                val left = view.second - y
                if (i == others.lastIndex && left > natural && l.resizesVertically()) l.h = left
            }
            val (w, h) = l.size
            placed[l] = Rect(0, y, w, h)
            y += h
        }
    }

    /** The stack's size, in layout pixels. */
    val stackSize: Pair<Int, Int>
        get() {
            if (needsLayout) doLayout()
            val rects = windows.map { windowRect(it) }
            return (rects.maxOfOrNull { it.right } ?: 0) to (rects.maxOfOrNull { it.bottom } ?: 0)
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
        return XmlNode(node.name, attrs, node.children.map { prefixFiles(it, prefix) })
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
                listOf(XmlNode(n.name, substituted(n.attrs), expand(n.children, dir, chain, depth + 1)))
            }
        }

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
                    // a value that is no RGB names another color (Bento's wasabi.list.background = color.display.bg)
                    // the later definition wins in either form (a skin's alias over the default set's RGB)
                    node["value"]?.let { v ->
                        val k = id.lowercase()
                        val rgb = parseRgb(v)
                        if (rgb != null) {
                            colors[k] = rgb
                            colorAliases.remove(k)
                        } else {
                            colorAliases[k] = v.lowercase()
                            colors.remove(k)
                        }
                    }
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
                    node.children.firstOrNull { it.name == "name" }?.let {
                        skinName =
                            it["text"] ?: skinName
                    }
                }
            }
        }
        for (c in node.children) collectDefs(c)
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

    /** Whether a component or window holder shows the component [guid]: by GUID or its alias. */
    private fun holds(
        node: XmlNode,
        guid: String,
    ): Boolean =
        listOf("hold", "param", "component").any { k ->
            node[k]?.split(';')?.any { v ->
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

    @Suppress("CyclomaticComplexMethod")
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
                    if (holds(node, PLAYLIST_GUID)) {
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
                        val g = buildXui(node, parent, defId)
                        node["content"]?.let { content ->
                            // the standard frame scripts build the content group themselves, but only when they run
                            if (g.systemObjects.isEmpty()) {
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
                        }
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
        fillGroup(g, XmlNode(node.name, node.attrs.filterKeys { it in gui }, node.children), defId)
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
        fun find(
            objs: List<GuiObject>,
            id: String,
        ): GuiObject? {
            val lower = id.lowercase()
            objs.firstOrNull { it.id == lower || (it is Group && it.instanceId == lower) }?.let { return it }
            for (o in objs) if (o is Group) o.findObject(lower)?.let { return it }
            return null
        }
        for ((m, before) in meta) {
            val targets = m["target"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: continue
            val existing = group.children.take(before)
            val scope = m["group"]?.let { find(existing, it) as? Group }
            for (t in targets) {
                val target = (if (scope != null) scope.findObject(t) else find(existing, t)) ?: continue
                when (m.name) {
                    "hideobject" -> target.hide()
                    "showobject" -> target.show()
                    "sendparams" -> m.attrs.forEach { (k, v) -> if (k != "target" && k != "group") target.setParam(k, v) }
                }
            }
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
                    try {
                        MakiParser.parse(bytes, file)
                    } catch (e: Exception) {
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
                if (event in s.events) {
                    if (s.dispatch(target, event, args) > 0) result = s.lastReturn
                }
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

    fun invalidate() {
        dirty = true
    }

    fun relayout() {
        needsLayout = true
        dirty = true
    }

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

    private fun layoutPass() {
        needsLayout = false
        arrange()
        for (l in windows) l.layoutRoot()
        val all = ArrayList<GuiObject>()

        fun walk(o: GuiObject) {
            all.add(o)
            if (o is Group) o.children.forEach { walk(it) }
        }
        for (l in windows) walk(l)
        objects = all
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

    /** Whether something moves by itself right now (a target slide or a playing animation): tick every frame. */
    val animating: Boolean get() = moving.isNotEmpty() || animations.any { it.playing }

    /** Script steps run since the last [tick] began, across every script. */
    val scriptSteps: Long get() = scripts.sumOf { it.stepsSinceRefill.toLong() }

    /** Timer events fired so far. */
    var timerFires = 0L
        private set

    /** Advances timers, tickers and player-driven objects. Returns true when a redraw is due. */
    fun tick(): Boolean {
        val t = now()
        for (s in scripts) s.refill()
        for (a in animations.filter { it.playing && t >= it.nextAt }) a.advance()
        for (o in moving.toList()) o.stepTarget(t)
        val due = timers.filter { it.running && t >= it.nextAt }
        for (timer in due) {
            timer.nextAt = t + maxOf(1, timer.delay)
            timerFires++
            dispatch(timer, "ontimer", emptyList())
        }
        val status = player.status
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
        }
        if (player.eqPreamp != lastPreamp) {
            lastPreamp = player.eqPreamp
            dispatchSystem("oneqpreampchanged", Value.Int((lastPreamp - EQ_FLAT).coerceIn(-127, 127)))
        }
        for (i in lastBands.indices) {
            val v = player.eqBands.getOrElse(i) { EQ_FLAT }
            if (v != lastBands[i]) {
                lastBands[i] = v
                // the band and its value on getEqBand's scale, -127..127
                dispatchSystem("oneqbandchanged", Value.Int(i), Value.Int((v - EQ_FLAT).coerceIn(-127, 127)))
            }
        }
        if (player.volume != lastVolume) {
            lastVolume = player.volume
            dispatchSystem("onvolumechanged", Value.Int(lastVolume))
        }
        if (needsLayout) doLayout()
        for (o in objects) {
            when (o) {
                is Slider -> o.sync()
                is Text -> if (o.tick(t)) dirty = true
                is Vis, is Status, is ProgressGrid -> if (status == Player.Status.PLAYING || o is Status) dirty = true
            }
        }
        if (objects.any { it is Text && it.display == "time" } && status == Player.Status.PLAYING) dirty = true
        if (needsLayout) doLayout()
        return dirty
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
        val out =
            drawn.mapNotNull { cmd ->
                when (cmd) {
                    is DrawCmd.Image -> {
                        fitToSheet(cmd)
                    }

                    is DrawCmd.WindowStart -> {
                        DrawCmd
                            .WindowStart(
                                cmd.region.mapPics(::fitToSheet) ?: Region.Op(null, emptyList(), emptyList(), emptyList()),
                            ).also { it.owner = cmd.owner }
                    }

                    else -> {
                        cmd
                    }
                }
            }
        for (cmd in out) {
            when (cmd) {
                is DrawCmd.Image -> cmd.gamma = gamma(cmd.bitmap.gammaGroup)
                is DrawCmd.WindowStart -> cmd.region.pics().forEach { it.gamma = gamma(it.bitmap.gammaGroup) }
                is DrawCmd.BitmapText -> cmd.gamma = gamma(cmd.font.gammaGroup)
                else -> Unit
            }
        }
        return out
    }

    /**
     * The part of [cmd] that lies on its sheet, with the destination cut the same way: Winamp draws
     * nothing for rows or columns a bitmap element asks for past the edge of its file (Winamp
     * Modern's display asks for 67 rows of a 63-row strip). Renderers then never see a source
     * outside the picture. Null when nothing is left.
     */
    private fun fitToSheet(cmd: DrawCmd.Image): DrawCmd.Image? {
        val b = cmd.bitmap
        val (sheetW, sheetH) = host.imageSize(b.file) ?: return cmd
        val sw = b.w ?: (sheetW - b.x)
        val sh = b.h ?: (sheetH - b.y)
        val fw = minOf(sw, sheetW - b.x)
        val fh = minOf(sh, sheetH - b.y)
        if (fw <= 0 || fh <= 0 || sw <= 0 || sh <= 0) return null
        if (fw == sw && fh == sh && b.w != null && b.h != null) return cmd
        // a stretched picture keeps its scale: the destination shrinks by the same share
        val dw = if (fw == sw) cmd.dst.width else cmd.dst.width * fw / sw
        val dh = if (fh == sh) cmd.dst.height else cmd.dst.height * fh / sh
        if (dw <= 0 || dh <= 0) return null
        return DrawCmd
            .Image(
                BitmapDef(b.id, b.file, b.x, b.y, fw, fh, b.gammaGroup),
                Rect(cmd.dst.left, cmd.dst.top, dw, dh),
                cmd.alpha,
            ).also {
                it.owner = cmd.owner
                it.smooth = cmd.smooth
            }
    }

    // ---- pointer -----------------------------------------------------------------------------

    /** The window under a point of the stack, or the nearest within [touchSlop] above or below. */
    private fun windowAt(
        x: Int,
        y: Int,
    ): Layout? {
        if (needsLayout) doLayout()
        // the one drawn last is on top: a docked window covers the main layout's empty drawer space
        return windows.lastOrNull { (x to y) in windowRect(it) }
            ?: windows
                .minByOrNull {
                    val r = windowRect(it)
                    maxOf(r.top - y, 0, y - (r.bottom - 1))
                }?.takeIf {
                    val r = windowRect(it)
                    maxOf(r.top - y, 0, y - (r.bottom - 1)) <= touchSlop
                }
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

    private fun touchable(o: GuiObject): Boolean =
        o.visible && o.enabled && o.effectiveAlpha > 0 && o.rect.width > 0 && o.rect.height > 0 &&
            generateSequence(o.parent) { it.parent }.all { it.visible }

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

        fun nearest(pick: (GuiObject) -> Boolean): GuiObject? =
            objects
                .asReversed()
                .asSequence()
                .filter { pick(it) && touchable(it) && it.layoutObject === window }
                .map { it to distance2(it, x, y) }
                .filter { it.second <= slop2 }
                .minByOrNull { it.second }
                ?.first
        nearest(::isControl)?.let { return it }
        if (hit != null && reacts(hit)) return hit
        return nearest { o -> !isControl(o) && scripts.any { it.listensTo(o) } } ?: hit
    }

    /** Where the captured object's window sits, to turn stack points into its own. */
    private var capturedOrigin = Rect(0, 0, 0, 0)

    /** Pointer events take points of the stack (see [windowRect]); objects hear points of their window. */
    fun pointerDown(
        sx: Int,
        sy: Int,
    ) {
        if (needsLayout) doLayout()
        val target = touchTarget(sx, sy) ?: return
        // the touched window takes the focus
        target.layoutObject?.let { l ->
            if (l !== focused) {
                focused = l
                relayout()
            }
        }
        capturedOrigin = target.layoutObject?.let { windowRect(it) } ?: Rect(0, 0, 0, 0)
        val x = sx - capturedOrigin.left
        val y = sy - capturedOrigin.top
        lastPointer = x to y
        captured = target
        target.pointerDown(x, y)
        dispatch(target, "onenterarea", emptyList())
    }

    fun pointerMove(
        sx: Int,
        sy: Int,
    ) {
        val x = sx - capturedOrigin.left
        val y = sy - capturedOrigin.top
        lastPointer = x to y
        captured?.pointerMove(x, y)
    }

    fun pointerUp(
        sx: Int,
        sy: Int,
    ) {
        val x = sx - capturedOrigin.left
        val y = sy - capturedOrigin.top
        lastPointer = x to y
        val target = captured ?: return
        captured = null
        // a finger that drifts a little still counts as on the control
        val r = target.rect
        val inside = x >= r.left - touchSlop && x < r.right + touchSlop && y >= r.top - touchSlop && y < r.bottom + touchSlop
        target.pointerUp(x, y, inside)
        dispatch(target, "onleavearea", emptyList())
    }

    fun longPress(
        sx: Int,
        sy: Int,
    ) {
        val target = captured ?: hitTest(sx, sy) ?: return
        captured = null
        val origin = target.layoutObject?.let { windowRect(it) } ?: Rect(0, 0, 0, 0)
        target.longPress(sx - origin.left, sy - origin.top)
    }

    fun doubleTap(
        sx: Int,
        sy: Int,
    ) {
        val target = hitTest(sx, sy) ?: return
        // objects hear points of their window, as with every other pointer event
        val origin = target.layoutObject?.let { windowRect(it) } ?: Rect(0, 0, 0, 0)
        target.doubleTap(sx - origin.left, sy - origin.top)
    }

    /** The user's pick from the last popup menu, by command id. The phone has no menu yet: nothing. */
    fun menuChoice(items: List<String>): Int = 0

    // ---- actions -----------------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod")
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
                invalidate()
            }

            "SWITCH" -> {
                param?.let { source.layoutObject?.container?.switchTo(it) }
            }

            // a docked window's close button puts it away, unless canclose="0"; the main window stays
            // (the app has no desktop to close it to)
            "CLOSE" -> {
                source.layoutObject?.container?.let { c -> if (c !== mainContainer && c.canClose) closeWindow(c) }
            }

            "MINIMIZE", "SYSMENU", "CONTROLMENU", "MENU" -> {
                Unit
            }

            "EQ_TOGGLE" -> {
                player.eqEnabled = !player.eqEnabled
            }

            "EQ_AUTO" -> {
                configFlags["eq_auto"] = !(configFlags["eq_auto"] ?: false)
            }

            "VIS_NEXT", "VIS_PREV", "VIS_MENU", "VIS_FS" -> {
                Unit
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

    /** The short name of a window a TOGGLE names by short name or component GUID. */
    private fun windowKind(id: String): String {
        val bare = id.trim('{', '}').lowercase()
        return TOGGLE_GUIDS.entries.firstOrNull { it.value == bare }?.key ?: bare
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
        configItems[k]?.let { return it }
        configItems.values.firstOrNull { it.guid?.lowercase() == k }?.let { return it }
        if (guid == null) return null
        return ConfigItemObj(this, key, guid).also {
            configItems[k] = it
            configItems[guid.lowercase()] = it
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

    /** Where the player says a slider should sit, or null for one the player does not drive. */
    fun sliderTarget(s: Slider): Int? =
        when (s.action) {
            "seek" -> {
                if (player.lengthMs >
                    0
                ) {
                    s.low + (player.positionMs.toLong() * (s.high - s.low) / player.lengthMs).toInt()
                } else {
                    s.low
                }
            }

            "volume" -> {
                s.low + player.volume * (s.high - s.low) / 255
            }

            "eq_band" -> {
                eqValue(s.param)?.let { s.low + it * (s.high - s.low) / 255 }
            }

            "eq_preamp" -> {
                s.low + player.eqPreamp * (s.high - s.low) / 255
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

    fun sliderMoved(
        s: Slider,
        final: Boolean,
    ) {
        val frac = (s.position - s.low).toDouble() / maxOf(1, s.high - s.low)
        when (s.action) {
            "seek" -> {
                if (final) {
                    player.seekTo((frac * player.lengthMs).toInt())
                    dispatchSystem("onseek", Value.Int(player.positionMs))
                }
            }

            "volume" -> {
                player.volume = (frac * 255).toInt()
            }

            "eq_band" -> {
                when {
                    s.param.equals("preamp", ignoreCase = true) -> player.eqPreamp = (frac * 255).toInt()
                    else -> s.param?.toIntOrNull()?.let { player.setEqBand(it - 1, (frac * 255).toInt()) }
                }
            }

            "eq_preamp" -> {
                player.eqPreamp = (frac * 255).toInt()
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
        const val MAX_SETTING_LENGTH = 65_536
        const val MAX_OBJECTS = 100_000
        const val MAX_CREATED = 20_000
        val FILE_NODES = setOf("bitmap", "bitmapfont", "truetypefont", "script")
        const val MAX_ALIAS_DEPTH = 8
        const val EQ_FLAT = 128
        const val PLAYLIST_GUID = "45f3f7c1-a6f3-4ee6-a15e-125e92fc3f8d"

        /** Winamp's GUID aliases, and the short names the host knows windows by. */
        val TOGGLE_GUIDS =
            mapOf(
                "pl" to "45f3f7c1-a6f3-4ee6-a15e-125e92fc3f8d",
                "playlist" to "45f3f7c1-a6f3-4ee6-a15e-125e92fc3f8d",
                "ml" to "6b0edf80-c9a5-11d3-9f26-00c04f39ffc6",
                "musiclibrary" to "6b0edf80-c9a5-11d3-9f26-00c04f39ffc6",
                "library" to "6b0edf80-c9a5-11d3-9f26-00c04f39ffc6",
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
