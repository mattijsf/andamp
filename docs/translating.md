# Translating Andamp

Andamp's text is in string resources, so a language is a set of XML files and needs no
Kotlin. English is the original. There are translations into Dutch, French, German and
Simplified Chinese. Each is a first pass: corrections from native speakers are welcome, as
is any other language.

## Where the text is

`app/src/main/res/values/` holds the English, one file per area:

| File | Holds |
|---|---|
| `strings_menu.xml` | the main menu, the playlist's menus, the visualizer's menu |
| `strings_prefs.xml` | Preferences, every page |
| `strings_library.xml` | the media library window |
| `strings_player.xml` | playlist prompts, the file info box, This Phone and its scan |
| `strings_equalizer.xml` | the equalizer's PRESETS menu and its dialogs |
| `strings_prompts.xml` | questions, notices and the buttons they share |
| `strings_screens.xml` | the skin museum, the welcome screen, About, skin and plug-in import |
| `strings_widget.xml` | what the home-screen widget says to a screen reader |

A translation is the same files in a directory named for the language:
`values-zh-rCN/` for Simplified Chinese, `values-de/` for German, `values-pt-rBR/` for
Brazilian Portuguese. The name is `values-` plus the language code, plus `-r` and a region
code where the region matters.

## Adding a language

1. Copy the `strings_*.xml` files from `values/` into the new directory.
2. Translate the text between the tags. Leave the `name` of each entry as it is.
3. Delete the entries marked `translatable="false"`: they are names, such as Andamp.
4. Check it: `./gradlew :app:testDebugUnitTest --tests '*TranslationsTest'`.
5. Open a pull request.

Nothing else has to change. The build lists the languages for Android from the directories
it finds, so on Android 13 and later the new one shows up under Settings > Apps > Andamp >
Language. On older versions Andamp follows the phone's language.

A translation does not have to be complete. An entry it leaves out is shown in English.

To improve a translation that exists, edit its file and open a pull request.

## What to keep as it is

- **Placeholders.** `%1$s` and `%2$d` are filled in by the app: a name, a count. Keep each
  one, with its number. They may change places, which is what the number is for: English
  "%1$d in %2$s" is "%2$s 中的 %1$d 个" in Chinese. `%%` is a percent sign.
- **Plurals.** A `<plurals>` entry has one `<item>` per form the language has. English has
  `one` and `other`. Chinese has only `other`. Use the forms your language needs; `other`
  is always one of them.
- **Apostrophes and quotes** are written `\'` and `\"`. Typographic ones (’ “ ”) need no
  backslash.
- **The comments** in the English files say where a text shows and what a placeholder
  holds. They do not need translating.

## What reads best

- **The media library window is narrow.** Its text sits in small cells on one line, in
  capitals in English. Keep those translations about as short as the English. A label
  that is wider than its cell is squeezed to fit, or, in the skin's pixel letters, cut
  short with an ellipsis. Either reads worse than a shorter word.
- **Winamp's words.** Where Winamp had a word for something, such as the menu entries and
  the window names, use the word that the Winamp translation into your language used, so
  the app reads the way people remember it. Use another word only where Winamp's would
  not be understood today. The tables below have the words used so far.
- **Names stay.** Andamp, Winamp, Milkdrop, AVS, projectM, the names of skins, equalizer
  presets, plug-ins and effects are not translated. Nor are a modern skin's color theme names
  and the labels it writes in its menus: they show as the skin wrote them.

## What stays in English

Some text is drawn with the bitmap letters of the skin, and a skin has letters only for
what Winamp wrote with them. That text cannot be translated: the song title in the main
window's scroller, the clock, the kbps and kHz readouts, and the title bars of the
windows. A playlist row in a script the skin has no letters for is drawn in a regular
font instead.

## The words used

Each table lists the words a translation uses for the things Winamp named, and where each
comes from, so that the next person keeps to them.

- **Winamp** is the text that came with Winamp in that language: the text of the Winamp
  installer, in the Winamp source that was published, under `Src/installer/winamp/languages/`,
  and the official language packs in the Winamp 5.666 full installer, which hold the program's
  menus and its texts for modern skins.
- **Audacious** is a free player with a Winamp-style interface, used where Winamp's word has
  not been checked.
- **Common usage** is the word most media players use today, where Winamp's word would
  not be understood or has gone out of use. The word Winamp used is given beside it. It
  was found by comparing the translations of six open-source players (VLC, Clementine,
  Rhythmbox, Audacious, DeaDBeeF and Quod Libet) and the help pages of two large music
  services in that language.
- **Andamp** is a word for something Winamp did not have.
- **Unverified** means the word has not been checked against Winamp's text in that language.
  If you know Winamp's word, a pull request that corrects the table and the strings is welcome.

### Dutch

| English | Nederlands | From |
|---|---|---|
| skin | skin | Winamp |
| modern skin | moderne skin | Winamp |
| Modern skins (beta) | Moderne skins (bèta) | Andamp |
| color theme | kleurthema | Winamp |
| Skin menu | skinmenu | Andamp |
| sharp pixels | scherpe pixels | Andamp |
| visualization | visualisatie | Winamp |
| Media Library | mediabibliotheek | Winamp |
| playlist | afspeellijst | common usage (Winamp: speellijst) |
| bookmark | bladwijzer | common usage (Winamp: favoriet) |
| preset | preset | common usage (Winamp: voorinstelling) |
| plug-in | plug-in | Winamp |
| Preferences | Voorkeuren | Winamp |
| Now playing | Nu speelt | Winamp |
| Always On Top | Altijd bovenop | Winamp |
| Double Size | Dubbele grootte | Winamp |
| Shuffle, Repeat | Shuffle, Herhalen | Winamp |
| Sort list, Reverse list, Randomize list | Lijst sorteren, Lijst omkeren, Lijst willekeurig maken | Winamp |
| Playlist Editor | Afspeellijsteditor | common usage (Winamp: speellijsteditor) |
| Jump to file, Jump to time | Spring naar bestand, Spring naar tijd | Winamp |
| Enqueue | In wachtrij | Winamp |
| music source | muziekbron | Andamp |
| effect, rack | effect, rack | Andamp |
| tip | fooi | Andamp |

### French

| English | Français | From |
|---|---|---|
| skin | skin | Winamp |
| modern skin | skin moderne | Winamp |
| Modern skins (beta) | Skins modernes (bêta) | Andamp |
| color theme | thème (menu: Thèmes) | Winamp |
| Skin menu | menu du skin | Andamp |
| sharp pixels | pixels nets | Andamp |
| visualization | visualisation | Winamp |
| Media Library | médiathèque | Winamp |
| playlist | liste de lecture | Winamp |
| bookmark | signet | Winamp |
| preset | préréglage | common usage (Winamp: présélection) |
| plug-in | plug-in | Winamp |
| Preferences | Préférences | Winamp |
| Now playing | Lecture en cours | Winamp |
| track | morceau | Winamp |
| Play, Playback | Lire, Lecture | Winamp |
| Equalizer | Égaliseur | Winamp |
| Playlist Editor | Liste de lecture | Winamp |
| Skin Browser | Liste des skins | Winamp |
| Always On Top, Double Size | Toujours visible, Taille double | Winamp |
| Shuffle, Repeat | Lecture aléatoire, Lecture en boucle | Winamp |
| Reverse list | Renverser la liste | Winamp |
| Sort list, Randomize list | Trier la liste, Mélanger la liste | Audacious |
| Jump to file, Jump to time | Aller au morceau, Aller à un moment précis | Winamp |
| Enqueue | Mettre à la suite | Winamp |
| music source | source de musique | Andamp |
| effect, rack | effet, rack | Andamp |
| tip | pourboire | Andamp |


### German

| English | Deutsch | From |
|---|---|---|
| skin | Skin | Winamp |
| modern skin | moderner Skin | Winamp |
| Modern skins (beta) | Moderne Skins (Beta) | Andamp |
| color theme | Farbschema (menu: Farbschemen) | Winamp |
| Skin menu | Skin-Menü | Andamp |
| sharp pixels | scharfe Pixel | Andamp |
| visualization | Visualisierung | Winamp |
| Media Library | Medienbibliothek | Winamp |
| playlist | Playlist | Winamp |
| bookmark | Lesezeichen | Winamp |
| preset | Voreinstellung | Winamp |
| plug-in | Plug-in | Winamp |
| Preferences | Einstellungen | Winamp |
| Jump to file | Springe zu Titel | Winamp |
| Now playing | Wird gerade gespielt | Winamp |
| Enqueue | Einreihen | Winamp |
| station | Sender | common usage (Winamp: Station) |
| track | Titel | Winamp |
| Equalizer, Options | Equalizer, Optionen | Winamp |
| Always On Top, Double Size | Immer im Vordergrund, Doppelte Größe | Winamp |
| Shuffle, Repeat | Zufallsmodus, Wiederholen | Winamp |
| Sort list, Reverse list, Randomize list | Liste sortieren, Liste umkehren, Liste mischen | Winamp |
| Playlist Editor | Playlist-Editor | Winamp |
| Jump to time | Springe zu Zeitmarke | Winamp |
| file info | Titelinformationen | Winamp |
| auto-load preset | automatische Voreinstellung | Winamp |
| music source | Musikquelle | Andamp |
| effect, rack | Effekt, Rack | Andamp |
| tip | Trinkgeld | Andamp |

### Simplified Chinese

| English | 简体中文 | From |
|---|---|---|
| skin | 面板 | Winamp |
| modern skin | 现代面板 | Winamp |
| Modern skins (beta) | 现代面板（测试版） | Andamp |
| color theme | 颜色主题 | Winamp |
| Skin menu | 面板菜单 | Andamp |
| sharp pixels | 锐利像素 | Andamp |
| load | 加载 | Winamp |
| visualization | 可视化效果 | Winamp |
| Media Library | 媒体库 | Winamp |
| playlist | 播放列表 | Winamp |
| bookmark | 书签 | Winamp |
| preset | 预设 | Winamp |
| plug-in | 插件 | Winamp |
| Jump to file, Jump to time | 跳到文件, 跳到时间 | Winamp |
| Playlist Editor | 播放列表编辑器 | Winamp |
| Equalizer | 均衡器 | Winamp |
| Double Size | 双倍大小 | Winamp |
| Sort list | 排序列表 | Andamp (Winamp: 排序) |
| Sort list by title, Reverse list, Randomize list | 按标题排列列表, 反转列表, 随机排列列表 | Winamp |
| Always On Top | 总在最上 | Winamp |
| Preferences | 首选项 | Winamp |
| Shuffle, Repeat | 随机, 重复 | Winamp |
| Enqueue | 排队 | Winamp |
| Previous, Next | 上一首, 下一首 | Winamp |
| music source | 音乐源 | Andamp |
| effect, rack | 效果, 机架 | Andamp |
| tip | 打赏 | Andamp |
