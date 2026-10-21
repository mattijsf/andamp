// SPDX-License-Identifier: GPL-3.0-or-later
// Generated from Webamp's transcription of std.mi (MIT, Jordan Eldredge). Do not edit by hand.
package nl.mattix.andamp.modernskin.maki

/** Return kinds: I int, F float, D double, B boolean, S string, V void, O object, A any. */
class ApiMethod(
    val params: Int,
    val ret: Char,
)

class ApiClass(
    val name: String,
    val parent: String?,
    val methods: Map<String, ApiMethod>,
)

/** The MAKI standard library: class GUIDs, their parents and each method's arity and return kind. */
@Suppress("LargeClass", "LongMethod", "MaxLineLength")
object StdApi {
    private fun m(
        params: Int,
        ret: Char,
    ) = ApiMethod(params, ret)

    val byGuid: Map<String, ApiClass> =
        mapOf(
            "516549710d874a5191e3a6b53235f3e7" to
                ApiClass("Object", null, mapOf("getclassname" to m(0, 'S'), "getid" to m(0, 'S'), "onnotify" to m(4, 'I'))),
            "d6f50f6493fa49b793f1ba66efae3e98" to
                ApiClass(
                    "System",
                    "Object",
                    mapOf(
                        "onscriptloaded" to m(0, 'V'),
                        "onscriptunloading" to m(0, 'V'),
                        "onquit" to m(0, 'V'),
                        "onsetxuiparam" to m(2, 'V'),
                        "onkeydown" to m(1, 'V'),
                        "onaccelerator" to m(3, 'V'),
                        "oncreatelayout" to m(1, 'V'),
                        "onshowlayout" to m(1, 'V'),
                        "onhidelayout" to m(1, 'V'),
                        "onviewportchanged" to m(2, 'V'),
                        "onstop" to m(0, 'V'),
                        "onplay" to m(0, 'V'),
                        "onpause" to m(0, 'V'),
                        "onresume" to m(0, 'V'),
                        "ontitlechange" to m(1, 'V'),
                        "ontitle2change" to m(1, 'V'),
                        "onurlchange" to m(1, 'V'),
                        "oninfochange" to m(1, 'V'),
                        "onstatusmsg" to m(1, 'V'),
                        "oneqbandchanged" to m(2, 'V'),
                        "oneqpreampchanged" to m(1, 'V'),
                        "oneqchanged" to m(1, 'V'),
                        "oneqfreqchanged" to m(1, 'V'),
                        "onvolumechanged" to m(1, 'V'),
                        "onseek" to m(1, 'V'),
                        "getcontainer" to m(1, 'O'),
                        "newdynamiccontainer" to m(1, 'O'),
                        "newgroup" to m(1, 'O'),
                        "newgroupaslayout" to m(1, 'O'),
                        "getnumcontainers" to m(0, 'I'),
                        "enumcontainer" to m(1, 'O'),
                        "enumembedguid" to m(1, 'S'),
                        "getwac" to m(1, 'O'),
                        "messagebox" to m(4, 'I'),
                        "getplayitemstring" to m(0, 'S'),
                        "getplayitemlength" to m(0, 'I'),
                        "getplayitemmetadatastring" to m(1, 'S'),
                        "getmetadatastring" to m(2, 'S'),
                        "getplayitemdisplaytitle" to m(0, 'S'),
                        "getcurrenttrackrating" to m(0, 'I'),
                        "oncurrenttrackrated" to m(1, 'V'),
                        "setcurrenttrackrating" to m(1, 'V'),
                        "getextfamily" to m(1, 'S'),
                        "getdecodername" to m(1, 'S'),
                        "playfile" to m(1, 'V'),
                        "getalbumart" to
                            m(
                                1,
                                'I',
                            ),
                        "downloadmedia" to
                            m(4, 'V'),
                        "downloadurl" to
                            m(3, 'V'),
                        "ondownloadfinished" to
                            m(
                                3,
                                'V',
                            ),
                        "getdownloadpath" to
                            m(
                                0,
                                'S',
                            ),
                        "setdownloadpath" to
                            m(
                                1,
                                'V',
                            ),
                        "enqueuefile" to
                            m(
                                1,
                                'V',
                            ),
                        "getleftvumeter" to
                            m(
                                0,
                                'I',
                            ),
                        "getrightvumeter" to
                            m(
                                0,
                                'I',
                            ),
                        "getvolume" to
                            m(
                                0,
                                'I',
                            ),
                        "setvolume" to
                            m(
                                1,
                                'V',
                            ),
                        "play" to
                            m(
                                0,
                                'V',
                            ),
                        "stop" to
                            m(
                                0,
                                'V',
                            ),
                        "pause" to
                            m(
                                0,
                                'V',
                            ),
                        "next" to
                            m(
                                0,
                                'V',
                            ),
                        "previous" to
                            m(
                                0,
                                'V',
                            ),
                        "eject" to
                            m(
                                0,
                                'V',
                            ),
                        "seekto" to
                            m(
                                1,
                                'V',
                            ),
                        "getposition" to
                            m(
                                0,
                                'I',
                            ),
                        "seteqband" to
                            m(
                                2,
                                'V',
                            ),
                        "seteqpreamp" to
                            m(
                                1,
                                'V',
                            ),
                        "seteq" to
                            m(
                                1,
                                'V',
                            ),
                        "geteqband" to
                            m(
                                1,
                                'I',
                            ),
                        "geteqpreamp" to
                            m(
                                0,
                                'I',
                            ),
                        "geteq" to
                            m(
                                0,
                                'I',
                            ),
                        "getmouseposx" to
                            m(
                                0,
                                'I',
                            ),
                        "getmouseposy" to
                            m(
                                0,
                                'I',
                            ),
                        "integertostring" to
                            m(
                                1,
                                'S',
                            ),
                        "stringtointeger" to
                            m(
                                1,
                                'I',
                            ),
                        "floattostring" to
                            m(
                                2,
                                'S',
                            ),
                        "stringtofloat" to
                            m(
                                1,
                                'F',
                            ),
                        "integertolongtime" to
                            m(
                                1,
                                'S',
                            ),
                        "integertotime" to
                            m(
                                1,
                                'S',
                            ),
                        "datetotime" to
                            m(
                                1,
                                'S',
                            ),
                        "datetolongtime" to
                            m(
                                1,
                                'S',
                            ),
                        "formatdate" to
                            m(
                                1,
                                'S',
                            ),
                        "formatlongdate" to
                            m(
                                1,
                                'S',
                            ),
                        "getdateyear" to
                            m(
                                1,
                                'I',
                            ),
                        "getdatemonth" to
                            m(
                                1,
                                'I',
                            ),
                        "getdateday" to
                            m(
                                1,
                                'I',
                            ),
                        "getdatedow" to
                            m(
                                1,
                                'I',
                            ),
                        "getdatedoy" to
                            m(
                                1,
                                'I',
                            ),
                        "getdatehour" to
                            m(
                                1,
                                'I',
                            ),
                        "getdatemin" to
                            m(
                                1,
                                'I',
                            ),
                        "getdatesec" to
                            m(
                                1,
                                'I',
                            ),
                        "getdatedst" to
                            m(
                                1,
                                'I',
                            ),
                        "getdate" to
                            m(
                                0,
                                'I',
                            ),
                        "strmid" to
                            m(
                                3,
                                'S',
                            ),
                        "strleft" to
                            m(
                                2,
                                'S',
                            ),
                        "strright" to
                            m(
                                2,
                                'S',
                            ),
                        "strsearch" to
                            m(
                                2,
                                'I',
                            ),
                        "strlen" to
                            m(
                                1,
                                'I',
                            ),
                        "strupper" to
                            m(
                                1,
                                'S',
                            ),
                        "strlower" to
                            m(
                                1,
                                'S',
                            ),
                        "urlencode" to
                            m(
                                1,
                                'S',
                            ),
                        "urldecode" to
                            m(
                                1,
                                'S',
                            ),
                        "parseatf" to
                            m(
                                1,
                                'S',
                            ),
                        "removepath" to
                            m(
                                1,
                                'S',
                            ),
                        "getpath" to
                            m(
                                1,
                                'S',
                            ),
                        "getextension" to
                            m(
                                1,
                                'S',
                            ),
                        "gettoken" to
                            m(
                                3,
                                'S',
                            ),
                        "sin" to
                            m(
                                1,
                                'D',
                            ),
                        "cos" to
                            m(
                                1,
                                'D',
                            ),
                        "tan" to
                            m(
                                1,
                                'D',
                            ),
                        "asin" to
                            m(
                                1,
                                'D',
                            ),
                        "acos" to
                            m(
                                1,
                                'D',
                            ),
                        "atan" to
                            m(
                                1,
                                'D',
                            ),
                        "atan2" to
                            m(
                                2,
                                'D',
                            ),
                        "pow" to
                            m(
                                2,
                                'D',
                            ),
                        "sqr" to
                            m(
                                1,
                                'D',
                            ),
                        "log10" to
                            m(
                                1,
                                'D',
                            ),
                        "ln" to
                            m(
                                1,
                                'D',
                            ),
                        "sqrt" to
                            m(
                                1,
                                'D',
                            ),
                        "random" to
                            m(
                                1,
                                'I',
                            ),
                        "setprivatestring" to
                            m(
                                3,
                                'V',
                            ),
                        "setprivateint" to
                            m(
                                3,
                                'V',
                            ),
                        "getprivatestring" to
                            m(
                                3,
                                'S',
                            ),
                        "getprivateint" to
                            m(
                                3,
                                'I',
                            ),
                        "setpublicstring" to
                            m(
                                2,
                                'V',
                            ),
                        "setpublicint" to
                            m(
                                2,
                                'V',
                            ),
                        "getpublicstring" to
                            m(
                                2,
                                'S',
                            ),
                        "getpublicint" to
                            m(
                                2,
                                'I',
                            ),
                        "getparam" to
                            m(
                                0,
                                'S',
                            ),
                        "getscriptgroup" to
                            m(
                                0,
                                'O',
                            ),
                        "getviewportwidth" to
                            m(
                                0,
                                'I',
                            ),
                        "getviewportwidthfromguiobject" to
                            m(
                                1,
                                'I',
                            ),
                        "getviewportwidthfrompoint" to
                            m(
                                2,
                                'I',
                            ),
                        "getmonitorwidth" to
                            m(
                                0,
                                'I',
                            ),
                        "getmonitorwidthfrompoint" to
                            m(
                                2,
                                'I',
                            ),
                        "getmonitorwidthfromguiobject" to
                            m(
                                1,
                                'I',
                            ),
                        "onmousemove" to
                            m(
                                2,
                                'V',
                            ),
                        "getviewportheight" to
                            m(
                                0,
                                'I',
                            ),
                        "getviewportheightfromguiobject" to
                            m(
                                1,
                                'I',
                            ),
                        "getviewportheightfrompoint" to
                            m(
                                2,
                                'I',
                            ),
                        "getmonitorheight" to
                            m(
                                0,
                                'I',
                            ),
                        "getmonitorheightfrompoint" to
                            m(
                                2,
                                'I',
                            ),
                        "getmonitorheightfromguiobject" to
                            m(
                                1,
                                'I',
                            ),
                        "getmonitorleft" to
                            m(
                                0,
                                'I',
                            ),
                        "getmonitorleftfromguiobject" to
                            m(
                                1,
                                'I',
                            ),
                        "getmonitorleftfrompoint" to
                            m(
                                2,
                                'I',
                            ),
                        "getmonitortop" to
                            m(
                                0,
                                'I',
                            ),
                        "getmonitortopfromguiobject" to
                            m(
                                1,
                                'I',
                            ),
                        "getmonitortopfrompoint" to
                            m(
                                2,
                                'I',
                            ),
                        "getviewportleft" to
                            m(
                                0,
                                'I',
                            ),
                        "getviewportleftfromguiobject" to
                            m(
                                1,
                                'I',
                            ),
                        "getviewportleftfrompoint" to
                            m(
                                2,
                                'I',
                            ),
                        "getviewporttop" to
                            m(
                                0,
                                'I',
                            ),
                        "getviewporttopfromguiobject" to
                            m(
                                1,
                                'I',
                            ),
                        "getviewporttopfrompoint" to
                            m(
                                2,
                                'I',
                            ),
                        "debugstring" to
                            m(
                                2,
                                'V',
                            ),
                        "ddesend" to
                            m(
                                3,
                                'V',
                            ),
                        "onlookforcomponent" to
                            m(
                                1,
                                'O',
                            ),
                        "getcurappleft" to
                            m(
                                0,
                                'I',
                            ),
                        "getcurapptop" to
                            m(
                                0,
                                'I',
                            ),
                        "getcurappwidth" to
                            m(
                                0,
                                'I',
                            ),
                        "getcurappheight" to
                            m(
                                0,
                                'I',
                            ),
                        "isappactive" to
                            m(
                                0,
                                'B',
                            ),
                        "getskinname" to
                            m(
                                0,
                                'S',
                            ),
                        "switchskin" to
                            m(
                                1,
                                'V',
                            ),
                        "isloadingskin" to
                            m(
                                0,
                                'I',
                            ),
                        "lockui" to
                            m(
                                0,
                                'V',
                            ),
                        "unlockui" to
                            m(
                                0,
                                'V',
                            ),
                        "getmainbrowser" to
                            m(
                                0,
                                'O',
                            ),
                        "popmainbrowser" to
                            m(
                                0,
                                'V',
                            ),
                        "navigateurl" to
                            m(
                                1,
                                'V',
                            ),
                        "navigateurlbrowser" to
                            m(
                                1,
                                'V',
                            ),
                        "onopenurl" to
                            m(
                                1,
                                'B',
                            ),
                        "isobjectvalid" to
                            m(
                                1,
                                'B',
                            ),
                        "integer" to
                            m(
                                1,
                                'I',
                            ),
                        "frac" to
                            m(
                                1,
                                'D',
                            ),
                        "gettimeofday" to
                            m(
                                0,
                                'I',
                            ),
                        "setmenutransparency" to
                            m(
                                1,
                                'V',
                            ),
                        "ongetcancelcomponent" to
                            m(
                                2,
                                'B',
                            ),
                        "getstatus" to
                            m(
                                0,
                                'I',
                            ),
                        "iskeydown" to
                            m(
                                1,
                                'I',
                            ),
                        "setclipboardtext" to
                            m(
                                1,
                                'V',
                            ),
                        "chr" to
                            m(
                                1,
                                'S',
                            ),
                        "translate" to
                            m(
                                1,
                                'S',
                            ),
                        "getstring" to
                            m(
                                2,
                                'S',
                            ),
                        "getlanguageid" to
                            m(
                                0,
                                'S',
                            ),
                        "selectfile" to
                            m(
                                3,
                                'S',
                            ),
                        "selectfolder" to
                            m(
                                3,
                                'S',
                            ),
                        "systemmenu" to
                            m(
                                0,
                                'V',
                            ),
                        "windowmenu" to
                            m(
                                0,
                                'V',
                            ),
                        "triggeraction" to
                            m(
                                3,
                                'V',
                            ),
                        "showwindow" to
                            m(
                                3,
                                'O',
                            ),
                        "hidewindow" to
                            m(
                                1,
                                'V',
                            ),
                        "hidenamedwindow" to
                            m(
                                1,
                                'V',
                            ),
                        "isnamedwindowvisible" to
                            m(
                                1,
                                'B',
                            ),
                        "setatom" to
                            m(
                                2,
                                'V',
                            ),
                        "getatom" to
                            m(
                                1,
                                'O',
                            ),
                        "invokedebugger" to
                            m(
                                0,
                                'V',
                            ),
                        "hasvideosupport" to
                            m(
                                0,
                                'I',
                            ),
                        "isvideo" to
                            m(
                                0,
                                'I',
                            ),
                        "isvideofullscreen" to
                            m(
                                0,
                                'I',
                            ),
                        "getidealvideowidth" to
                            m(
                                0,
                                'I',
                            ),
                        "getidealvideoheight" to
                            m(
                                0,
                                'I',
                            ),
                        "isminimized" to
                            m(
                                0,
                                'I',
                            ),
                        "minimizeapplication" to
                            m(
                                0,
                                'V',
                            ),
                        "restoreapplication" to
                            m(
                                0,
                                'V',
                            ),
                        "activateapplication" to
                            m(
                                0,
                                'V',
                            ),
                        "getplaylistlength" to
                            m(
                                0,
                                'I',
                            ),
                        "getplaylistindex" to
                            m(
                                0,
                                'I',
                            ),
                        "clearplaylist" to
                            m(
                                0,
                                'V',
                            ),
                        "isdesktopalphaavailable" to
                            m(
                                0,
                                'B',
                            ),
                        "istransparencyavailable" to
                            m(
                                0,
                                'B',
                            ),
                        "onshownotification" to
                            m(
                                0,
                                'I',
                            ),
                        "getsonginfotext" to
                            m(
                                0,
                                'S',
                            ),
                        "getsonginfotexttranslated" to
                            m(
                                0,
                                'S',
                            ),
                        "getvisband" to
                            m(
                                2,
                                'I',
                            ),
                        "getruntimeversion" to
                            m(
                                0,
                                'D',
                            ),
                        "iswa2componentvisible" to
                            m(
                                1,
                                'I',
                            ),
                        "hidewa2component" to
                            m(
                                1,
                                'V',
                            ),
                        "isproversion" to
                            m(
                                0,
                                'B',
                            ),
                        "getwinampversion" to
                            m(
                                0,
                                'S',
                            ),
                        "getbuildnumber" to
                            m(
                                0,
                                'I',
                            ),
                        "getfilesize" to
                            m(
                                1,
                                'I',
                            ),
                    ),
                ),
            "e90dc47b840d4ae7b02c040bd275f7fc" to
                ApiClass(
                    "Container",
                    "Object",
                    mapOf(
                        "onswitchtolayout" to m(1, 'V'),
                        "onbeforeswitchtolayout" to m(2, 'V'),
                        "setxmlparam" to m(2, 'V'),
                        "onhidelayout" to m(1, 'V'),
                        "onshowlayout" to m(1, 'V'),
                        "getlayout" to m(1, 'O'),
                        "getnumlayouts" to m(0, 'I'),
                        "enumlayout" to m(1, 'O'),
                        "switchtolayout" to m(1, 'V'),
                        "show" to m(0, 'V'),
                        "hide" to m(0, 'V'),
                        "close" to m(0, 'V'),
                        "toggle" to m(0, 'V'),
                        "isdynamic" to m(0, 'I'),
                        "setname" to m(1, 'V'),
                        "getname" to m(0, 'S'),
                        "getguid" to m(0, 'S'),
                        "getcurlayout" to m(0, 'O'),
                        "onaddcontent" to m(3, 'V'),
                    ),
                ),
            "00c074a0fea249a0be8dfabbdb161640" to
                ApiClass(
                    "Wac",
                    "Object",
                    mapOf(
                        "getguid" to m(0, 'S'),
                        "getname" to m(0, 'S'),
                        "sendcommand" to m(4, 'I'),
                        "show" to m(0, 'V'),
                        "hide" to m(0, 'V'),
                        "isvisible" to m(0, 'B'),
                        "onnotify" to m(3, 'V'),
                        "onshow" to m(0, 'V'),
                        "onhide" to m(0, 'V'),
                        "setstatusbar" to m(1, 'V'),
                        "getstatusbar" to m(0, 'B'),
                    ),
                ),
            "b2023ab5434d4ba1beae59637503f3c6" to
                ApiClass(
                    "List",
                    "Object",
                    mapOf(
                        "additem" to m(1, 'V'),
                        "removeitem" to m(1, 'V'),
                        "enumitem" to m(1, 'A'),
                        "finditem" to m(1, 'I'),
                        "finditem2" to m(2, 'I'),
                        "getnumitems" to m(0, 'I'),
                        "removeall" to m(0, 'V'),
                    ),
                ),
            "87c65778e74349fe85f909cc532afd56" to
                ApiClass(
                    "BitList",
                    "Object",
                    mapOf(
                        "getitem" to m(1, 'B'),
                        "setitem" to m(2, 'V'),
                        "setsize" to m(1, 'V'),
                        "getsize" to m(0, 'I'),
                    ),
                ),
            "38603665461b42a7aa75d83f6667bf73" to
                ApiClass(
                    "Map",
                    "Object",
                    mapOf(
                        "getvalue" to m(2, 'I'),
                        "getargbvalue" to m(3, 'I'),
                        "inregion" to m(2, 'B'),
                        "loadmap" to m(1, 'V'),
                        "getwidth" to m(0, 'I'),
                        "getheight" to m(0, 'I'),
                        "getregion" to m(0, 'O'),
                    ),
                ),
            "f4787af4b2bb4ef79cfbe74ba9bea88d" to
                ApiClass(
                    "PopupMenu",
                    "Object",
                    mapOf(
                        "addsubmenu" to m(2, 'V'),
                        "addcommand" to m(4, 'V'),
                        "addseparator" to m(0, 'V'),
                        "popatxy" to m(2, 'I'),
                        "popatmouse" to m(0, 'I'),
                        "getnumcommands" to m(0, 'I'),
                        "checkcommand" to m(2, 'V'),
                        "disablecommand" to m(2, 'V'),
                    ),
                ),
            "3a370c023cbf439f84f186885bcf1e36" to
                ApiClass(
                    "Region",
                    "Object",
                    mapOf(
                        "add" to m(1, 'V'),
                        "sub" to m(1, 'V'),
                        "offset" to m(2, 'V'),
                        "stretch" to m(1, 'V'),
                        "copy" to m(1, 'V'),
                        "loadfrommap" to m(3, 'V'),
                        "loadfrombitmap" to m(1, 'V'),
                        "getboundingboxx" to m(0, 'I'),
                        "getboundingboxy" to m(0, 'I'),
                        "getboundingboxw" to m(0, 'I'),
                        "getboundingboxh" to m(0, 'I'),
                    ),
                ),
            "5d0c5bb67de14b1fa70f8d1659941941" to
                ApiClass(
                    "Timer",
                    "Object",
                    mapOf(
                        "ontimer" to m(0, 'V'),
                        "setdelay" to m(1, 'V'),
                        "getdelay" to m(0, 'I'),
                        "start" to m(0, 'V'),
                        "stop" to m(0, 'V'),
                        "isrunning" to m(0, 'V'),
                        "getskipped" to m(0, 'I'),
                    ),
                ),
            "a5376fa14e94411a83f605ec5eea5f0a" to
                ApiClass(
                    "FeedWatcher",
                    "Object",
                    mapOf(
                        "setfeed" to m(1, 'I'),
                        "releasefeed" to m(0, 'V'),
                        "onfeedchange" to m(1, 'V'),
                    ),
                ),
            "4ee3e199c6364bec97cd78bc9c8628b0" to
                ApiClass(
                    "GuiObject",
                    "Object",
                    mapOf(
                        "show" to m(0, 'V'),
                        "hide" to m(0, 'V'),
                        "isvisible" to m(0, 'I'),
                        "onsetvisible" to m(1, 'V'),
                        "setalpha" to m(1, 'V'),
                        "getalpha" to m(0, 'I'),
                        "onleftbuttonup" to m(2, 'V'),
                        "onleftbuttondown" to m(2, 'V'),
                        "onrightbuttonup" to m(2, 'V'),
                        "onrightbuttondown" to m(2, 'V'),
                        "onrightbuttondblclk" to m(2, 'V'),
                        "onleftbuttondblclk" to m(2, 'V'),
                        "onmousewheelup" to m(2, 'I'),
                        "onmousewheeldown" to m(2, 'I'),
                        "onmousemove" to m(2, 'V'),
                        "onenterarea" to m(0, 'V'),
                        "onleavearea" to m(0, 'V'),
                        "setenabled" to m(1, 'V'),
                        "getenabled" to m(0, 'B'),
                        "onenable" to m(1, 'V'),
                        "resize" to m(4, 'V'),
                        "onresize" to m(4, 'V'),
                        "ismouseover" to m(2, 'B'),
                        "getleft" to m(0, 'I'),
                        "gettop" to m(0, 'I'),
                        "getwidth" to m(0, 'I'),
                        "getheight" to m(0, 'I'),
                        "settargetx" to m(1, 'V'),
                        "settargety" to m(1, 'V'),
                        "settargetw" to m(1, 'V'),
                        "settargeth" to m(1, 'V'),
                        "settargeta" to m(1, 'V'),
                        "settargetspeed" to m(1, 'V'),
                        "gototarget" to m(0, 'V'),
                        "ontargetreached" to m(0, 'V'),
                        "canceltarget" to m(0, 'V'),
                        "reversetarget" to m(1, 'V'),
                        "onstartup" to m(0, 'V'),
                        "isgoingtotarget" to m(0, 'B'),
                        "setxmlparam" to m(2, 'V'),
                        "getxmlparam" to m(1, 'S'),
                        "init" to m(1, 'V'),
                        "bringtofront" to m(0, 'V'),
                        "bringtoback" to m(0, 'V'),
                        "bringabove" to m(1, 'V'),
                        "bringbelow" to m(1, 'V'),
                        "getguix" to m(0, 'I'),
                        "getguiy" to m(0, 'I'),
                        "getguiw" to m(0, 'I'),
                        "getguih" to m(0, 'I'),
                        "getguirelatx" to m(0, 'I'),
                        "getguirelaty" to m(0, 'I'),
                        "getguirelatw" to m(0, 'I'),
                        "getguirelath" to
                            m(0, 'I'),
                        "isactive" to
                            m(0, 'B'),
                        "getparent" to
                            m(
                                0,
                                'O',
                            ),
                        "getparentlayout" to
                            m(
                                0,
                                'O',
                            ),
                        "gettopparent" to
                            m(
                                0,
                                'O',
                            ),
                        "runmodal" to
                            m(
                                0,
                                'I',
                            ),
                        "endmodal" to
                            m(
                                1,
                                'V',
                            ),
                        "findobject" to
                            m(
                                1,
                                'O',
                            ),
                        "findobjectxy" to
                            m(
                                2,
                                'O',
                            ),
                        "getname" to
                            m(
                                0,
                                'S',
                            ),
                        "clienttoscreenx" to
                            m(
                                1,
                                'I',
                            ),
                        "clienttoscreeny" to
                            m(
                                1,
                                'I',
                            ),
                        "clienttoscreenw" to
                            m(
                                1,
                                'I',
                            ),
                        "clienttoscreenh" to
                            m(
                                1,
                                'I',
                            ),
                        "screentoclientx" to
                            m(
                                1,
                                'I',
                            ),
                        "screentoclienty" to
                            m(
                                1,
                                'I',
                            ),
                        "screentoclientw" to
                            m(
                                1,
                                'I',
                            ),
                        "screentoclienth" to
                            m(
                                1,
                                'I',
                            ),
                        "getautowidth" to
                            m(
                                0,
                                'I',
                            ),
                        "getautoheight" to
                            m(
                                0,
                                'I',
                            ),
                        "setfocus" to
                            m(
                                0,
                                'V',
                            ),
                        "onchar" to
                            m(
                                1,
                                'V',
                            ),
                        "onaccelerator" to
                            m(
                                1,
                                'V',
                            ),
                        "ismouseoverrect" to
                            m(
                                0,
                                'B',
                            ),
                        "getinterface" to
                            m(
                                1,
                                'O',
                            ),
                        "ondragenter" to
                            m(
                                0,
                                'V',
                            ),
                        "ondragover" to
                            m(
                                2,
                                'V',
                            ),
                        "ondragleave" to
                            m(
                                0,
                                'V',
                            ),
                        "onkeydown" to
                            m(
                                1,
                                'V',
                            ),
                        "onkeyup" to
                            m(
                                1,
                                'V',
                            ),
                        "ongetfocus" to
                            m(
                                0,
                                'V',
                            ),
                        "onkillfocus" to
                            m(
                                0,
                                'V',
                            ),
                        "sendaction" to
                            m(
                                6,
                                'I',
                            ),
                        "onaction" to
                            m(
                                7,
                                'I',
                            ),
                    ),
                ),
            "45be95e520724191935cbb5ff9f117fd" to
                ApiClass(
                    "Group",
                    "GuiObject",
                    mapOf(
                        "getobject" to m(1, 'O'),
                        "getnumobjects" to m(0, 'I'),
                        "enumobject" to m(1, 'O'),
                        "oncreateobject" to m(1, 'V'),
                        "getmouseposx" to m(0, 'I'),
                        "getmouseposy" to m(0, 'I'),
                        "islayout" to m(0, 'B'),
                    ),
                ),
            "60906d4e537e482eb004cc9461885672" to
                ApiClass(
                    "Layout",
                    "Group",
                    mapOf(
                        "ondock" to m(1, 'V'),
                        "onundock" to m(0, 'V'),
                        "onscale" to m(1, 'V'),
                        "getscale" to m(0, 'D'),
                        "setscale" to m(1, 'V'),
                        "setdesktopalpha" to m(1, 'V'),
                        "getdesktopalpha" to m(0, 'B'),
                        "getcontainer" to m(0, 'O'),
                        "center" to m(0, 'V'),
                        "onmove" to m(0, 'V'),
                        "onendmove" to m(0, 'V'),
                        "onuserresize" to m(4, 'V'),
                        "snapadjust" to m(4, 'V'),
                        "getsnapadjusttop" to m(0, 'I'),
                        "getsnapadjustright" to m(0, 'I'),
                        "getsnapadjustleft" to m(0, 'I'),
                        "getsnapadjustbottom" to m(0, 'I'),
                        "setredrawonresize" to m(1, 'V'),
                        "beforeredock" to m(0, 'V'),
                        "redock" to m(0, 'V'),
                        "istransparencysafe" to m(0, 'B'),
                        "islayoutanimationsafe" to m(0, 'B'),
                        "onmouseenterlayout" to m(0, 'V'),
                        "onmouseleavelayout" to m(0, 'V'),
                        "onsnapadjustchanged" to m(0, 'V'),
                    ),
                ),
            "403abcc06f224bd68ba410c829932547" to
                ApiClass(
                    "WindowHolder",
                    "GuiObject",
                    mapOf(
                        "setregionfrommap" to m(3, 'V'),
                        "setregion" to m(1, 'V'),
                        "getcontent" to m(0, 'O'),
                        "getguid" to m(0, 'S'),
                        "getcomponentname" to m(0, 'S'),
                        "ongetwac" to m(1, 'V'),
                        "ongiveupwac" to m(1, 'V'),
                        "getwac" to m(0, 'O'),
                        "setacceptwac" to m(1, 'V'),
                    ),
                ),
            "97aa3e4df4d04fa8817b0af22a454983" to
                ApiClass(
                    "ComponentBucket",
                    "GuiObject",
                    mapOf(
                        "getmaxheight" to m(0, 'I'),
                        "getmaxwidth" to m(0, 'I'),
                        "setscroll" to m(1, 'I'),
                        "getscroll" to m(0, 'I'),
                        "getnumchildren" to m(0, 'I'),
                        "enumchildren" to m(1, 'O'),
                    ),
                ),
            "64e4bbfa81f449d9b0c0a85b2ec3bcfd" to
                ApiClass(
                    "Edit",
                    "GuiObject",
                    mapOf(
                        "onenter" to m(0, 'V'),
                        "onabort" to m(0, 'V'),
                        "onidleeditupdate" to m(0, 'V'),
                        "oneditupdate" to m(0, 'V'),
                        "settext" to m(1, 'V'),
                        "setautoenter" to m(1, 'V'),
                        "getautoenter" to m(0, 'I'),
                        "gettext" to m(0, 'S'),
                        "selectall" to m(0, 'V'),
                        "enter" to m(0, 'V'),
                        "setidleenabled" to m(1, 'V'),
                        "getidleenabled" to m(0, 'I'),
                    ),
                ),
            "62b65e3f375e408d8dea76814ab91b77" to
                ApiClass(
                    "Slider",
                    "GuiObject",
                    mapOf(
                        "onsetposition" to m(1, 'V'),
                        "onpostedposition" to m(1, 'V'),
                        "onsetfinalposition" to m(1, 'V'),
                        "setposition" to m(1, 'V'),
                        "getposition" to m(0, 'I'),
                        "lock" to m(0, 'V'),
                        "unlock" to m(0, 'V'),
                    ),
                ),
            "ce4f97be77b04e199956d49833c96c27" to
                ApiClass(
                    "Vis",
                    "GuiObject",
                    mapOf(
                        "onframe" to m(0, 'V'),
                        "setrealtime" to m(1, 'V'),
                        "getrealtime" to m(0, 'B'),
                        "getmode" to m(0, 'I'),
                        "setmode" to m(1, 'V'),
                        "nextmode" to m(0, 'V'),
                    ),
                ),
            "a8c2200d51eb4b2aba7f5d4bc65d4c71" to
                ApiClass(
                    "Browser",
                    "GuiObject",
                    mapOf(
                        "navigateurl" to m(1, 'V'),
                        "back" to m(0, 'V'),
                        "forward" to m(0, 'V'),
                        "stop" to m(0, 'V'),
                        "refresh" to m(0, 'V'),
                        "home" to m(0, 'V'),
                        "settargetname" to m(1, 'V'),
                        "onbeforenavigate" to m(3, 'B'),
                        "ondocumentcomplete" to m(1, 'V'),
                        "ondocumentready" to m(1, 'V'),
                        "getdocumenttitle" to m(0, 'S'),
                        "onnavigateerror" to m(2, 'V'),
                        "setcancelieerrorpage" to m(1, 'V'),
                        "scrape" to m(0, 'V'),
                        "onmedialink" to m(1, 'S'),
                    ),
                ),
            "8d1eba38489e483eb9608d1f43c5c405" to ApiClass("EqVis", "GuiObject", mapOf()),
            "0f08c940af394b2380f3b8c48f7ebb59" to ApiClass("Status", "GuiObject", mapOf()),
            "efaa8672310e41fab7dc85a9525bcb4b" to
                ApiClass(
                    "Text",
                    "GuiObject",
                    mapOf(
                        "settext" to m(1, 'V'),
                        "setalternatetext" to m(1, 'V'),
                        "gettext" to m(0, 'S'),
                        "gettextwidth" to m(0, 'I'),
                        "ontextchanged" to m(1, 'V'),
                    ),
                ),
            "7dfd324437514e7cbf4082ae5f3adc33" to ApiClass("Title", "GuiObject", mapOf()),
            "5ab9fa159a7d4557abc86557a6c67ca9" to
                ApiClass(
                    "Layer",
                    "GuiObject",
                    mapOf(
                        "onbeginresize" to m(4, 'V'),
                        "onendresize" to m(4, 'V'),
                        "fx_oninit" to m(0, 'V'),
                        "fx_onframe" to m(0, 'V'),
                        "fx_ongetpixelr" to m(4, 'D'),
                        "fx_ongetpixeld" to m(4, 'D'),
                        "fx_ongetpixelx" to m(4, 'D'),
                        "fx_ongetpixely" to m(4, 'D'),
                        "fx_ongetpixela" to m(4, 'D'),
                        "setregionfrommap" to m(3, 'V'),
                        "setregion" to m(1, 'V'),
                        "fx_setenabled" to m(1, 'V'),
                        "fx_getenabled" to m(0, 'B'),
                        "fx_setwrap" to m(1, 'V'),
                        "fx_getwrap" to m(0, 'B'),
                        "fx_setrect" to m(1, 'V'),
                        "fx_getrect" to m(0, 'B'),
                        "fx_setbgfx" to m(1, 'V'),
                        "fx_getbgfx" to m(0, 'B'),
                        "fx_setclear" to m(1, 'V'),
                        "fx_getclear" to m(0, 'B'),
                        "fx_setspeed" to m(1, 'V'),
                        "fx_getspeed" to m(0, 'I'),
                        "fx_setrealtime" to m(1, 'V'),
                        "fx_getrealtime" to m(0, 'B'),
                        "fx_setlocalized" to m(1, 'V'),
                        "fx_getlocalized" to m(0, 'B'),
                        "fx_setbilinear" to m(1, 'V'),
                        "fx_getbilinear" to m(0, 'B'),
                        "fx_setalphamode" to m(1, 'V'),
                        "fx_getalphamode" to m(0, 'B'),
                        "fx_setgridsize" to m(2, 'V'),
                        "fx_update" to m(0, 'V'),
                        "fx_restart" to m(0, 'V'),
                        "isinvalid" to m(0, 'B'),
                    ),
                ),
            "698eddcd8f1e4fec9b12f944f909ff45" to
                ApiClass(
                    "Button",
                    "GuiObject",
                    mapOf(
                        "onactivate" to m(1, 'V'),
                        "onleftclick" to m(0, 'V'),
                        "onrightclick" to m(0, 'V'),
                        "setactivated" to m(1, 'V'),
                        "setactivatednocallback" to m(1, 'V'),
                        "getactivated" to m(0, 'B'),
                        "leftclick" to m(0, 'V'),
                        "rightclick" to m(0, 'V'),
                    ),
                ),
            "6b64cd275a264c4b8c59e6a70cf6493a" to
                ApiClass(
                    "AnimatedLayer",
                    "Layer",
                    mapOf(
                        "onplay" to m(0, 'V'),
                        "onpause" to m(0, 'V'),
                        "onresume" to m(0, 'V'),
                        "onstop" to m(0, 'V'),
                        "onframe" to m(1, 'V'),
                        "setspeed" to m(1, 'V'),
                        "gotoframe" to m(1, 'V'),
                        "setstartframe" to m(1, 'V'),
                        "setendframe" to m(1, 'V'),
                        "setautoreplay" to m(1, 'V'),
                        "play" to m(0, 'V'),
                        "stop" to m(0, 'V'),
                        "pause" to m(0, 'V'),
                        "isplaying" to m(0, 'B'),
                        "ispaused" to m(0, 'B'),
                        "isstopped" to m(0, 'B'),
                        "getstartframe" to m(0, 'I'),
                        "getendframe" to m(0, 'I'),
                        "getlength" to m(0, 'I'),
                        "getdirection" to m(0, 'I'),
                        "getautoreplay" to m(0, 'B'),
                        "getcurframe" to m(0, 'I'),
                        "setrealtime" to m(1, 'V'),
                    ),
                ),
            "6dcb05e48ac448c2b19349f0910ef54a" to
                ApiClass(
                    "AlbumArtLayer",
                    "Layer",
                    mapOf(
                        "refresh" to m(0, 'V'),
                        "isloading" to m(0, 'V'),
                        "onalbumartloaded" to m(1, 'V'),
                    ),
                ),
            "b4dccfff81fe4bcc961b720fd5be0fff" to
                ApiClass("ToggleButton", "Button", mapOf("ontoggle" to m(1, 'V'), "getcurcfgval" to m(0, 'I'))),
            "01e28ce1b05911d5979fe4de6f51760a" to
                ApiClass(
                    "GroupList",
                    "GuiObject",
                    mapOf(
                        "instantiate" to m(2, 'O'),
                        "getnumitems" to m(0, 'I'),
                        "enumitem" to m(1, 'O'),
                        "removeall" to m(0, 'V'),
                        "scrolltopercent" to m(1, 'V'),
                        "setredraw" to m(1, 'V'),
                    ),
                ),
            "80f0f8bd1ba542a6a0933236a00c8d4a" to
                ApiClass(
                    "CfgGroup",
                    "Group",
                    mapOf(
                        "cfggetint" to m(0, 'I'),
                        "cfgsetint" to m(1, 'V'),
                        "cfggetstring" to m(0, 'S'),
                        "cfggetfloat" to m(0, 'F'),
                        "cfgsetfloat" to m(1, 'V'),
                        "cfgsetstring" to m(1, 'V'),
                        "oncfgchanged" to m(0, 'V'),
                        "cfggetguid" to m(0, 'S'),
                        "cfggetname" to m(0, 'S'),
                    ),
                ),
            "cdcb785d81f242538f0561b872283cfa" to ApiClass("QueryList", "GuiObject", mapOf("onresetquery" to m(0, 'V'))),
            "9b2e341b6c9840fa8b850c1b6ee89405" to
                ApiClass(
                    "MouseRedir",
                    "GuiObject",
                    mapOf(
                        "setredirection" to m(1, 'V'),
                        "getredirection" to m(0, 'O'),
                        "setregionfrommap" to m(3, 'V'),
                        "setregion" to m(1, 'V'),
                    ),
                ),
            "36d59b7103fd4af897950502b7db267a" to
                ApiClass(
                    "DropDownList",
                    "GuiObject",
                    mapOf(
                        "getitemselected" to m(0, 'S'),
                        "onselect" to m(2, 'V'),
                        "setlistheight" to m(1, 'V'),
                        "openlist" to m(0, 'V'),
                        "closelist" to m(0, 'V'),
                        "setitems" to m(1, 'V'),
                        "additem" to m(1, 'I'),
                        "delitem" to m(1, 'V'),
                        "finditem" to m(1, 'I'),
                        "getnumitems" to m(0, 'I'),
                        "selectitem" to m(2, 'V'),
                        "getitemtext" to m(1, 'S'),
                        "getselected" to m(0, 'I'),
                        "getselectedtext" to m(0, 'S'),
                        "getcustomtext" to m(0, 'S'),
                        "deleteallitems" to m(0, 'V'),
                        "setnoitemtext" to m(1, 'V'),
                    ),
                ),
            "7fd5f210acc448dfa6a05451576cdc76" to ApiClass("LayoutStatus", "GuiObject", mapOf("callme" to m(1, 'V'))),
            "b5baa53505b34dcbadc1e618d28f6896" to
                ApiClass("TabSheet", "GuiObject", mapOf("getcurpage" to m(0, 'I'), "setcurpage" to m(1, 'V'))),
            "6129fec1dab74d51916501ca0c1b70db" to
                ApiClass(
                    "GuiList",
                    "GuiObject",
                    mapOf(
                        "addcolumn" to m(3, 'I'),
                        "getnumcolumns" to m(0, 'I'),
                        "getcolumnwidth" to m(1, 'I'),
                        "setcolumnwidth" to m(2, 'V'),
                        "getcolumnlabel" to m(1, 'S'),
                        "setcolumnlabel" to m(2, 'V'),
                        "getcolumnnumeric" to m(1, 'I'),
                        "setcolumndynamic" to m(2, 'V'),
                        "iscolumndynamic" to m(1, 'I'),
                        "invalidatecolumns" to m(0, 'V'),
                        "getnumitems" to m(0, 'I'),
                        "getitemcount" to m(0, 'I'),
                        "additem" to m(1, 'I'),
                        "insertitem" to m(2, 'I'),
                        "getlastaddeditempos" to m(0, 'I'),
                        "setsubitem" to m(3, 'V'),
                        "deleteallitems" to m(0, 'V'),
                        "deletebypos" to m(1, 'I'),
                        "getitemlabel" to m(2, 'S'),
                        "getsubitemtext" to m(2, 'S'),
                        "setitemlabel" to m(2, 'V'),
                        "invalidateitem" to m(1, 'I'),
                        "getfirstitemvisible" to m(0, 'I'),
                        "getlastitemvisible" to m(0, 'I'),
                        "setitemicon" to m(2, 'V'),
                        "getitemicon" to m(1, 'S'),
                        "setminimumsize" to m(1, 'V'),
                        "getwantautodeselect" to m(0, 'I'),
                        "setwantautodeselect" to m(1, 'V'),
                        "onsetvisible" to m(1, 'V'),
                        "setautosort" to m(1, 'V'),
                        "setfontsize" to m(1, 'I'),
                        "getfontsize" to m(0, 'I'),
                        "getheaderheight" to m(0, 'I'),
                        "getpreventmultipleselection" to m(0, 'I'),
                        "setpreventmultipleselection" to m(1, 'I'),
                        "setshowicons" to m(1, 'V'),
                        "getshowicons" to m(0, 'I'),
                        "seticonwidth" to m(1, 'I'),
                        "seticonheight" to m(1, 'I'),
                        "geticonwidth" to m(0, 'V'),
                        "geticonheight" to m(0, 'V'),
                        "next" to m(0, 'V'),
                        "previous" to m(0, 'V'),
                        "pagedown" to
                            m(0, 'V'),
                        "pageup" to
                            m(0, 'V'),
                        "home" to
                            m(
                                0,
                                'V',
                            ),
                        "end" to
                            m(
                                0,
                                'V',
                            ),
                        "reset" to
                            m(
                                0,
                                'V',
                            ),
                        "ensureitemvisible" to
                            m(
                                1,
                                'V',
                            ),
                        "scrollabsolute" to
                            m(
                                1,
                                'I',
                            ),
                        "scrollrelative" to
                            m(
                                1,
                                'I',
                            ),
                        "scrollleft" to
                            m(
                                1,
                                'V',
                            ),
                        "scrollright" to
                            m(
                                1,
                                'V',
                            ),
                        "scrollup" to
                            m(
                                1,
                                'V',
                            ),
                        "scrolldown" to
                            m(
                                1,
                                'V',
                            ),
                        "jumptonext" to
                            m(
                                1,
                                'V',
                            ),
                        "scrolltoitem" to
                            m(
                                1,
                                'V',
                            ),
                        "selectcurrent" to
                            m(
                                0,
                                'V',
                            ),
                        "selectfirstentry" to
                            m(
                                0,
                                'V',
                            ),
                        "getitemselected" to
                            m(
                                1,
                                'I',
                            ),
                        "isitemfocused" to
                            m(
                                1,
                                'I',
                            ),
                        "getitemfocused" to
                            m(
                                0,
                                'I',
                            ),
                        "setitemfocused" to
                            m(
                                1,
                                'V',
                            ),
                        "getfirstitemselected" to
                            m(
                                0,
                                'I',
                            ),
                        "getnextitemselected" to
                            m(
                                1,
                                'I',
                            ),
                        "selectall" to
                            m(
                                0,
                                'I',
                            ),
                        "deselectall" to
                            m(
                                0,
                                'I',
                            ),
                        "invertselection" to
                            m(
                                0,
                                'I',
                            ),
                        "setselectionstart" to
                            m(
                                1,
                                'V',
                            ),
                        "setselectionend" to
                            m(
                                1,
                                'V',
                            ),
                        "setselected" to
                            m(
                                2,
                                'V',
                            ),
                        "toggleselection" to
                            m(
                                2,
                                'V',
                            ),
                        "resort" to
                            m(
                                0,
                                'V',
                            ),
                        "getsortdirection" to
                            m(
                                0,
                                'I',
                            ),
                        "getsortcolumn" to
                            m(
                                0,
                                'I',
                            ),
                        "setsortcolumn" to
                            m(
                                1,
                                'V',
                            ),
                        "setsortdirection" to
                            m(
                                1,
                                'V',
                            ),
                        "moveitem" to
                            m(
                                2,
                                'V',
                            ),
                        "onselectall" to
                            m(
                                0,
                                'V',
                            ),
                        "ondelete" to
                            m(
                                0,
                                'V',
                            ),
                        "ondoubleclick" to
                            m(
                                1,
                                'V',
                            ),
                        "onleftclick" to
                            m(
                                1,
                                'V',
                            ),
                        "onsecondleftclick" to
                            m(
                                1,
                                'V',
                            ),
                        "onrightclick" to
                            m(
                                1,
                                'I',
                            ),
                        "oncolumndblclick" to
                            m(
                                3,
                                'I',
                            ),
                        "oncolumnlabelclick" to
                            m(
                                3,
                                'I',
                            ),
                        "onitemselection" to
                            m(
                                2,
                                'V',
                            ),
                        "oniconleftclick" to
                            m(
                                3,
                                'I',
                            ),
                    ),
                ),
            "d59514f7ed3645e8980f3f4ea0522cd9" to
                ApiClass(
                    "GuiTree",
                    "GuiObject",
                    mapOf(
                        "onwantautocontextmenu" to m(0, 'I'),
                        "onmousewheelup" to m(2, 'I'),
                        "onmousewheeldown" to m(2, 'I'),
                        "oncontextmenu" to m(2, 'I'),
                        "onchar" to m(1, 'I'),
                        "onitemrecvdrop" to m(1, 'V'),
                        "onlabelchange" to m(1, 'V'),
                        "onitemselected" to m(1, 'V'),
                        "onitemdeselected" to m(1, 'V'),
                        "getnumrootitems" to m(0, 'I'),
                        "enumrootitem" to m(1, 'O'),
                        "jumptonext" to m(1, 'V'),
                        "ensureitemvisible" to m(1, 'V'),
                        "getcontentswidth" to m(0, 'I'),
                        "getcontentsheight" to m(0, 'I'),
                        "addtreeitem" to m(4, 'O'),
                        "removetreeitem" to m(1, 'I'),
                        "movetreeitem" to m(2, 'V'),
                        "deleteallitems" to m(0, 'V'),
                        "expanditem" to m(1, 'I'),
                        "expanditemdeferred" to m(1, 'V'),
                        "collapseitem" to m(1, 'I'),
                        "collapseitemdeferred" to m(1, 'V'),
                        "selectitem" to m(1, 'V'),
                        "selectitemdeferred" to m(1, 'V'),
                        "delitemdeferred" to m(1, 'V'),
                        "hiliteitem" to m(1, 'V'),
                        "unhiliteitem" to m(1, 'V'),
                        "getcuritem" to m(0, 'O'),
                        "hittest" to m(2, 'O'),
                        "edititemlabel" to m(1, 'V'),
                        "canceleditlabel" to m(1, 'V'),
                        "setautoedit" to m(1, 'V'),
                        "getautoedit" to m(0, 'I'),
                        "getbylabel" to m(2, 'O'),
                        "setsorted" to m(1, 'V'),
                        "getsorted" to m(0, 'I'),
                        "sorttreeitems" to m(0, 'V'),
                        "getsibling" to m(1, 'O'),
                        "setautocollapse" to m(1, 'V'),
                        "setfontsize" to m(1, 'I'),
                        "getfontsize" to m(0, 'I'),
                        "getnumvisiblechilditems" to
                            m(
                                1,
                                'I',
                            ),
                        "getnumvisibleitems" to m(0, 'I'),
                        "enumvisibleitems" to
                            m(1, 'O'),
                        "enumvisiblechilditems" to
                            m(2, 'O'),
                        "enumallitems" to
                            m(1, 'O'),
                        "getitemrectx" to
                            m(
                                1,
                                'I',
                            ),
                        "getitemrecty" to
                            m(
                                1,
                                'I',
                            ),
                        "getitemrectw" to
                            m(
                                1,
                                'I',
                            ),
                        "getitemrecth" to
                            m(
                                1,
                                'I',
                            ),
                        "getitemfrompoint" to
                            m(
                                2,
                                'O',
                            ),
                    ),
                ),
            "9b3b4b82667a420e8ffc794115809c02" to
                ApiClass(
                    "TreeItem",
                    "Object",
                    mapOf(
                        "getnumchildren" to m(0, 'I'),
                        "setlabel" to m(1, 'V'),
                        "getlabel" to m(0, 'S'),
                        "ensurevisible" to m(0, 'V'),
                        "getnthchild" to m(1, 'O'),
                        "getchild" to m(0, 'O'),
                        "getchildsibling" to m(1, 'O'),
                        "getsibling" to m(0, 'O'),
                        "getparent" to m(0, 'O'),
                        "editlabel" to m(0, 'V'),
                        "hassubitems" to m(0, 'I'),
                        "setsorted" to m(1, 'V'),
                        "setchildtab" to m(1, 'V'),
                        "issorted" to m(0, 'I'),
                        "iscollapsed" to m(0, 'I'),
                        "isexpanded" to m(0, 'I'),
                        "invalidate" to m(0, 'V'),
                        "isselected" to m(0, 'I'),
                        "ishilited" to m(0, 'I'),
                        "sethilited" to m(1, 'V'),
                        "collapse" to m(0, 'I'),
                        "expand" to m(0, 'I'),
                        "gettree" to m(0, 'O'),
                        "ontreeadd" to m(0, 'V'),
                        "ontreeremove" to m(0, 'V'),
                        "onselect" to m(0, 'V'),
                        "ondeselect" to m(0, 'V'),
                        "onleftdoubleclick" to m(0, 'I'),
                        "onrightdoubleclick" to m(0, 'I'),
                        "onchar" to m(1, 'I'),
                        "onexpand" to m(0, 'V'),
                        "oncollapse" to m(0, 'V'),
                        "onbeginlabeledit" to m(0, 'I'),
                        "onendlabeledit" to m(1, 'I'),
                        "oncontextmenu" to m(2, 'I'),
                    ),
                ),
            "1d8631c880d047929f98bd5d36b49136" to
                ApiClass(
                    "MenuButton",
                    "GuiObject",
                    mapOf(
                        "onopenmenu" to m(0, 'V'),
                        "onclosemenu" to m(0, 'V'),
                        "onselectitem" to m(1, 'V'),
                        "openmenu" to m(0, 'V'),
                        "closemenu" to m(0, 'V'),
                    ),
                ),
            "c7ed319953194798986360b15a298caa" to
                ApiClass(
                    "CheckBox",
                    "GuiObject",
                    mapOf(
                        "ontoggle" to m(1, 'V'),
                        "setchecked" to m(1, 'V'),
                        "ischecked" to m(0, 'I'),
                        "settext" to m(1, 'V'),
                        "gettext" to m(0, 'S'),
                    ),
                ),
            "2d2d1376be0a4cb9bc0c57e6e4c999f5" to
                ApiClass(
                    "Form",
                    "GuiObject",
                    mapOf(
                        "getcontentsheight" to m(0, 'I'),
                        "newcell" to m(1, 'V'),
                        "nextrow" to m(0, 'V'),
                        "deleteall" to m(0, 'V'),
                    ),
                ),
            "e2bbc14d84f64173bdb3b2eb2f665550" to
                ApiClass(
                    "Frame",
                    "GuiObject",
                    mapOf(
                        "getposition" to m(0, 'I'),
                        "setposition" to m(1, 'V'),
                        "onsetposition" to m(1, 'V'),
                    ),
                ),
            "73c00594961f401b9b1b672427ac4165" to
                ApiClass(
                    "Menu",
                    "GuiObject",
                    mapOf(
                        "setmenugroup" to m(1, 'V'),
                        "getmenugroup" to m(0, 'S'),
                        "setmenu" to m(1, 'V'),
                        "getmenu" to m(0, 'S'),
                        "spawnmenu" to m(1, 'V'),
                        "cancelmenu" to m(0, 'V'),
                        "setnormalid" to m(1, 'V'),
                        "setdownid" to m(1, 'V'),
                        "sethoverid" to m(1, 'V'),
                        "onopenmenu" to m(0, 'V'),
                        "onclosemenu" to m(0, 'V'),
                        "nextmenu" to m(0, 'V'),
                        "previousmenu" to m(0, 'V'),
                    ),
                ),
            "345beebc0229492190be6cb6a49a79d9" to
                ApiClass(
                    "PlEdit",
                    "Object",
                    mapOf(
                        "getnumtracks" to m(0, 'I'),
                        "getcurrentindex" to m(0, 'I'),
                        "getnumselectedtracks" to m(0, 'I'),
                        "getnextselectedtrack" to m(1, 'I'),
                        "showcurrentlyplayingtrack" to m(0, 'V'),
                        "showtrack" to m(1, 'V'),
                        "enqueuefile" to m(1, 'V'),
                        "clear" to m(0, 'V'),
                        "removetrack" to m(1, 'V'),
                        "swaptracks" to m(2, 'V'),
                        "moveup" to m(1, 'V'),
                        "movedown" to m(1, 'V'),
                        "moveto" to m(2, 'V'),
                        "playtrack" to m(1, 'V'),
                        "getrating" to m(1, 'I'),
                        "setrating" to m(2, 'V'),
                        "gettitle" to m(1, 'S'),
                        "getlength" to m(1, 'S'),
                        "getmetadata" to m(2, 'S'),
                        "getfilename" to m(1, 'S'),
                        "onpleditmodified" to m(0, 'V'),
                    ),
                ),
            "61a7abad7d7941f6b1d0e1808603a4f4" to
                ApiClass(
                    "PlDir",
                    "Object",
                    mapOf(
                        "getnumitems" to m(0, 'I'),
                        "getitemname" to m(1, 'S'),
                        "showcurrentlyplayingentry" to m(0, 'V'),
                        "refresh" to m(0, 'V'),
                        "renameitem" to m(2, 'V'),
                        "enqueueitem" to m(1, 'V'),
                        "playitem" to m(1, 'V'),
                    ),
                ),
            "593dba22d0774976b952f4713655400b" to
                ApiClass("Config", "Object", mapOf("getitem" to m(1, 'O'), "getitembyguid" to m(1, 'O'), "newitem" to m(2, 'O'))),
            "d40302823aab4d87878d12326fadfcd5" to
                ApiClass(
                    "ConfigItem",
                    "Object",
                    mapOf(
                        "getattribute" to m(1, 'O'),
                        "newattribute" to m(2, 'O'),
                        "getguid" to m(1, 'S'),
                        "getname" to m(0, 'S'),
                    ),
                ),
            "24dec283b76e4a368ccc9e24c46b6c73" to
                ApiClass(
                    "ConfigAttribute",
                    "Object",
                    mapOf(
                        "setdata" to m(1, 'V'),
                        "getdata" to m(0, 'S'),
                        "ondatachanged" to m(0, 'V'),
                        "getparentitem" to m(0, 'O'),
                        "getattributename" to m(0, 'S'),
                    ),
                ),
            "b2ad3f2b31ed4e31bc6de9951cd555bb" to ApiClass("WinampConfig", "Object", mapOf("getgroup" to m(1, 'O'))),
            "fc17844ec72b4518a068a8f930a5ba80" to
                ApiClass(
                    "WinampConfigGroup",
                    "Object",
                    mapOf(
                        "getbool" to m(1, 'B'),
                        "setbool" to m(2, 'V'),
                        "getint" to m(1, 'I'),
                        "setint" to m(1, 'V'),
                        "getstring" to m(1, 'S'),
                        "setstring" to m(1, 'V'),
                    ),
                ),
            "b8e867b027154da7a5ba53dba1fcfeac" to
                ApiClass(
                    "Application",
                    "Object",
                    mapOf(
                        "getapplicationname" to m(0, 'S'),
                        "getversionstring" to m(0, 'S'),
                        "getversionnumberstring" to m(0, 'S'),
                        "getbuildnumber" to m(0, 'I'),
                        "getguid" to m(0, 'S'),
                        "getcommandline" to m(0, 'S'),
                        "shutdown" to m(0, 'V'),
                        "cancelshutdown" to m(0, 'V'),
                        "isshuttingdown" to m(0, 'B'),
                        "getapplicationpath" to m(0, 'S'),
                        "getsettingspath" to m(0, 'S'),
                        "getworkingpath" to m(0, 'S'),
                        "setworkingpath" to m(1, 'V'),
                    ),
                ),
            "836f8b2ee0d14db4937f0d0a04c8dcd1" to
                ApiClass("File", "Object", mapOf("load" to m(1, 'V'), "exists" to m(0, 'B'), "getsize" to m(0, 'I'))),
            "417ffb69987f4be88d87d9965eeec868" to
                ApiClass(
                    "XmlDoc",
                    "File",
                    mapOf(
                        "parser_addcallback" to m(1, 'V'),
                        "parser_start" to m(0, 'V'),
                        "parser_oncallback" to m(4, 'S'),
                        "parser_onclosecallback" to m(2, 'S'),
                        "parser_onerror" to m(5, 'S'),
                        "parser_destroy" to m(0, 'S'),
                    ),
                ),
        )

    val byName: Map<String, ApiClass> = byGuid.values.associateBy { it.name.lowercase() }

    fun guidOf(name: String): String? = byGuid.entries.firstOrNull { it.value.name.equals(name, ignoreCase = true) }?.key

    /** Looks a method up on a class or any ancestor. */
    fun method(
        guid: String,
        name: String,
    ): ApiMethod? {
        var cls = byGuid[guid.lowercase()]
        val lower = name.lowercase()
        while (cls != null) {
            cls.methods[lower]?.let { return it }
            cls = cls.parent?.let { byName[it.lowercase()] }
        }
        return null
    }

    fun isA(
        guid: String,
        ancestorName: String,
    ): Boolean {
        var cls = byGuid[guid.lowercase()]
        while (cls != null) {
            if (cls.name.equals(ancestorName, ignoreCase = true)) return true
            cls = cls.parent?.let { byName[it.lowercase()] }
        }
        return false
    }
}
