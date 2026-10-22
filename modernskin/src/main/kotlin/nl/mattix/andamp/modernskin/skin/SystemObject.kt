// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import nl.mattix.andamp.modernskin.maki.MakiObject
import nl.mattix.andamp.modernskin.maki.StdApi
import nl.mattix.andamp.modernskin.maki.Value
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The `System` object a script talks to: the player, the clock, strings and the group the script
 * lives in. One per script instance, since `getScriptGroup()` and `getParam()` differ per place.
 * [started] once its onScriptLoaded ran: xui parameters wait for that.
 */
@Suppress("TooManyFunctions")
class SystemObject(
    private val rt: SkinRuntime,
    val group: Group?,
    val param: String,
    val scriptId: String,
) : MakiObject {
    var started = false

    override val makiGuid: String get() = StdApi.guidOf("System") ?: ""

    private val player get() = rt.player

    private fun str(s: String) = Value.Str(s)

    private fun int(i: Int) = Value.Int(i)

    private fun num(d: Double) = Value.Num(d)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun makiCall(
        name: String,
        args: List<Value>,
    ): Value? =
        when (name) {
            "getclassname" -> {
                str("System")
            }

            "getid" -> {
                str("system")
            }

            "getscriptgroup" -> {
                Value.of(group)
            }

            "getparam" -> {
                str(param)
            }

            "getcontainer" -> {
                Value.of(rt.container(args[0].asString))
            }

            "getnumcontainers" -> {
                int(rt.containers.size)
            }

            "enumcontainer" -> {
                Value.of(rt.containers.getOrNull(args[0].asInt))
            }

            "newgroup" -> {
                Value.of(rt.instantiateGroup(args[0].asString))
            }

            "newdynamiccontainer", "newgroupaslayout" -> {
                Value.Null
            }

            // the compiler-made prologue wants 2..65535 here; Winamp answers its own version
            "getruntimeversion" -> {
                num(5.666)
            }

            "getversionstring", "getwinampversion" -> {
                str("5.66")
            }

            "getskinname" -> {
                str(rt.skinName)
            }

            "getplayitemstring" -> {
                str(player.title)
            }

            "getplayitemdisplaytitle" -> {
                str(player.title)
            }

            "getplayitemmetadatastring", "getmetadatastring" -> {
                str(
                    // a file without tags answers nothing for its fields; the display title comes from the file name
                    when (args.last().asString.lowercase()) {
                        "title" -> player.tags["title"] ?: ""
                        "bitrate" -> "${player.bitrateKbps * 1000}"
                        "srate" -> "${player.sampleRateKhz * 1000}"
                        "length" -> "${player.lengthMs}"
                        else -> player.tags[args.last().asString.lowercase()] ?: ""
                    },
                )
            }

            "getplayitemlength" -> {
                int(player.lengthMs)
            }

            "getposition" -> {
                int(player.positionMs)
            }

            "getstatus" -> {
                int(
                    when (player.status) {
                        Player.Status.PLAYING -> 1
                        Player.Status.PAUSED -> -1
                        Player.Status.STOPPED -> 0
                    },
                )
            }

            "seekto" -> {
                Value.Null.also { player.seekTo(args[0].asInt) }
            }

            "play" -> {
                Value.Null.also { player.play() }
            }

            "stop" -> {
                Value.Null.also { player.stop() }
            }

            "pause" -> {
                Value.Null.also { player.pause() }
            }

            "next" -> {
                Value.Null.also { player.next() }
            }

            "previous" -> {
                Value.Null.also { player.previous() }
            }

            "eject" -> {
                Value.Null.also { player.eject() }
            }

            "getvolume" -> {
                int(player.volume)
            }

            "setvolume" -> {
                Value.Null.also { player.volume = args[0].asInt.coerceIn(0, 255) }
            }

            "getleftvumeter" -> {
                int(player.vu().first)
            }

            "getrightvumeter" -> {
                int(player.vu().second)
            }

            "getvisband" -> {
                int((player.spectrum().getOrNull(args[1].asInt) ?: 0f).times(255).toInt())
            }

            "geteq" -> {
                int(if (player.eqEnabled) 1 else 0)
            }

            "seteq" -> {
                Value.Null.also { player.eqEnabled = args[0].truthy }
            }

            // scripts see the equalizer from -127 to 127 with 0 flat; the player keeps 0-255 with 128 flat
            "geteqband" -> {
                int((player.eqBands.getOrElse(args[0].asInt) { EQ_FLAT } - EQ_FLAT).coerceIn(-127, 127))
            }

            "seteqband" -> {
                Value.Null.also { player.setEqBand(args[0].asInt, (args[1].asInt + EQ_FLAT).coerceIn(0, 255)) }
            }

            "geteqpreamp" -> {
                int((player.eqPreamp - EQ_FLAT).coerceIn(-127, 127))
            }

            "seteqpreamp" -> {
                Value.Null.also { player.eqPreamp = (args[0].asInt + EQ_FLAT).coerceIn(0, 255) }
            }

            "getplaylistlength" -> {
                int(1)
            }

            "getplaylistindex" -> {
                int(0)
            }

            // the current song's, stopped or not (Winamp3x shows 64 and 8 after Stop); nothing without one
            "getsonginfotext", "getsonginfotexttranslated" -> {
                val channels = if (player.channels == 1) "mono" else "stereo"
                str(if (player.title.isEmpty()) "" else "${player.bitrateKbps}kbps $channels ${player.sampleRateKhz}kHz")
            }

            "integertostring" -> {
                str(args[0].asInt.toString())
            }

            "stringtointeger" -> {
                int(args[0].asInt)
            }

            "floattostring" -> {
                str("%.${args[1].asInt}f".format(args[0].asDouble))
            }

            "stringtofloat" -> {
                num(args[0].asDouble)
            }

            "integertotime" -> {
                str(formatTime(args[0].asInt))
            }

            // hours, minutes and seconds (Wasabi SDK std.mi)
            "integertolongtime" -> {
                val t = args[0].asInt
                val a = Math.abs(t)
                str((if (t < 0) "-" else "") + "%d:%02d:%02d".format(a / 3600, a / 60 % 60, a % 60))
            }

            "integer" -> {
                int(Math.round(args[0].asDouble).toInt())
            }

            "frac" -> {
                num(args[0].asDouble - Math.floor(args[0].asDouble))
            }

            "chr" -> {
                str(args[0].asInt.toChar().toString())
            }

            "strlen" -> {
                int(args[0].asString.length)
            }

            "strupper" -> {
                str(args[0].asString.uppercase())
            }

            "strlower" -> {
                str(args[0].asString.lowercase())
            }

            "strmid" -> {
                val from = args[1].asInt.coerceAtLeast(0)
                str(args[0].asString.drop(from).take(args[2].asInt.coerceAtLeast(0)))
            }

            "strleft" -> {
                str(args[0].asString.take(args[1].asInt.coerceAtLeast(0)))
            }

            "strright" -> {
                str(args[0].asString.takeLast(args[1].asInt.coerceAtLeast(0)))
            }

            "strsearch" -> {
                int(args[0].asString.indexOf(args[1].asString))
            }

            "gettoken" -> {
                val tokens = args[0].asString.split(args[1].asString)
                str(tokens.getOrElse(args[2].asInt) { "" })
            }

            "getextension" -> {
                str(args[0].asString.substringAfterLast('.', ""))
            }

            "removepath" -> {
                str(args[0].asString.substringAfterLast('\\').substringAfterLast('/'))
            }

            "getpath" -> {
                str(args[0].asString.substringBeforeLast('\\').substringBeforeLast('/'))
            }

            "urlencode", "urldecode", "translate" -> {
                args[0]
            }

            "getprivateint" -> {
                int(rt.privateInts["${args[0].asString}/${args[1].asString}".lowercase()] ?: args[2].asInt)
            }

            "setprivateint" -> {
                Value.Null.also {
                    rt.storePrivate(rt.privateInts, "${args[0].asString}/${args[1].asString}", args[2].asInt)
                }
            }

            "getprivatestring" -> {
                str(rt.privateStrings["${args[0].asString}/${args[1].asString}".lowercase()] ?: args[2].asString)
            }

            "setprivatestring" -> {
                Value.Null.also {
                    rt.storePrivate(rt.privateStrings, "${args[0].asString}/${args[1].asString}", args[2].asString)
                }
            }

            "getpublicint" -> {
                int(rt.privateInts["public/${args[0].asString}".lowercase()] ?: args[1].asInt)
            }

            "setpublicint" -> {
                Value.Null.also { rt.storePrivate(rt.privateInts, "public/${args[0].asString}", args[1].asInt) }
            }

            "getpublicstring" -> {
                str(rt.privateStrings["public/${args[0].asString}".lowercase()] ?: args[1].asString)
            }

            "setpublicstring" -> {
                Value.Null.also { rt.storePrivate(rt.privateStrings, "public/${args[0].asString}", args[1].asString) }
            }

            "messagebox" -> {
                int(0).also { rt.host.log("messagebox: ${args[0].asString} [${args[1].asString}]") }
            }

            "debugstring" -> {
                Value.Null.also { rt.host.log("debug: ${args[0].asString}") }
            }

            "getviewportwidth", "getmonitorwidth", "getviewportwidthfromguiobject", "getviewportwidthfrompoint",
            "getmonitorwidthfromguiobject", "getmonitorwidthfrompoint",
            -> {
                int(rt.viewportW)
            }

            "getviewportheight", "getmonitorheight", "getviewportheightfromguiobject", "getviewportheightfrompoint",
            "getmonitorheightfromguiobject", "getmonitorheightfrompoint",
            -> {
                int(rt.viewportH)
            }

            "getviewportleft", "getviewporttop", "getmonitorleft", "getmonitortop", "getviewportleftfromguiobject",
            "getviewporttopfromguiobject", "getmonitorleftfromguiobject", "getmonitortopfromguiobject",
            "getviewportleftfrompoint", "getviewporttopfrompoint", "getmonitorleftfrompoint", "getmonitortopfrompoint",
            -> {
                int(0)
            }

            "getcurappleft" -> {
                int(rt.mainContainer?.x ?: 0)
            }

            "getcurapptop" -> {
                int(rt.mainContainer?.y ?: 0)
            }

            "getcurappwidth" -> {
                int(rt.mainLayout?.rect?.width ?: 0)
            }

            "getcurappheight" -> {
                int(rt.mainLayout?.rect?.height ?: 0)
            }

            "getmouseposx" -> {
                int(rt.lastPointer.first)
            }

            "getmouseposy" -> {
                int(rt.lastPointer.second)
            }

            "gettimeofday" -> {
                int((rt.now() % MS_PER_DAY).toInt())
            }

            "getdate" -> {
                int((rt.now() / 1000).toInt())
            }

            "random" -> {
                int(if (args[0].asInt <= 0) 0 else (Math.random() * args[0].asInt).toInt())
            }

            "sin" -> {
                num(sin(args[0].asDouble))
            }

            "cos" -> {
                num(cos(args[0].asDouble))
            }

            "tan" -> {
                num(tan(args[0].asDouble))
            }

            "asin" -> {
                num(asin(args[0].asDouble))
            }

            "acos" -> {
                num(acos(args[0].asDouble))
            }

            "atan" -> {
                num(atan(args[0].asDouble))
            }

            "atan2" -> {
                num(Math.atan2(args[0].asDouble, args[1].asDouble))
            }

            "pow" -> {
                num(args[0].asDouble.pow(args[1].asDouble))
            }

            "sqr" -> {
                num(args[0].asDouble * args[0].asDouble)
            }

            "sqrt" -> {
                num(sqrt(args[0].asDouble))
            }

            "ln" -> {
                num(ln(args[0].asDouble))
            }

            "log10" -> {
                num(Math.log10(args[0].asDouble))
            }

            "exp" -> {
                num(Math.exp(args[0].asDouble))
            }

            "lockui", "unlockui", "ddesend", "setcurrenttrackrating", "navigateurl", "enqueuefile", "playfile",
            "showwindow", "hidewindow", "selectfile", "selectfolder", "selectfilestart", "selectfileend", "setplaylistindex",
            "setclipboardtext", "setmenutransparency", "systemmenu", "windowmenu", "triggeraction", "popupmenu",
            -> {
                Value.Null
            }

            "isobjectvalid" -> {
                Value.Bool(args[0].asObject != null)
            }

            "istransparencyavailable", "isdesktopalphaavailable", "isproversion" -> {
                Value.Bool(false)
            }

            "isvideo", "isvideofullscreen", "iskeydown", "isminimized", "getcurrenttrackrating",
            "isappactive", "isloadingskin", "getplaylistcount", "getdownloadpath", "getfilesize",
            -> {
                int(0)
            }

            "getwac" -> {
                Value.Null
            }

            "hasvideosupport" -> {
                int(1)
            }

            "getextfamily" -> {
                str("Audio")
            }

            "getdecodername" -> {
                str(player.decoderName)
            }

            "getalbumart" -> {
                int(0)
            }

            "getskinpath", "getskinpathinfo" -> {
                str("")
            }

            else -> {
                null
            }
        }

    private fun formatTime(seconds: Int): String {
        val s = Math.abs(seconds)
        return (if (seconds < 0) "-" else "") + "%d:%02d".format(s / 60, s % 60)
    }

    private companion object {
        const val MS_PER_DAY = 24L * 60 * 60 * 1000
        const val EQ_FLAT = 128
    }
}
