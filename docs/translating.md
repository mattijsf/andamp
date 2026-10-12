# Translating Andamp

Andamp's text is in string resources, so a language is a set of XML files and needs no
Kotlin. English is the original. Simplified Chinese is the first translation, and it is a
first pass: corrections from native speakers are welcome, as is any other language.

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
| `strings_screens.xml` | the skin museum, the welcome screen, About, plug-in import |
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
  capitals in English. Keep those translations about as short as the English.
- **Winamp's words.** Where Winamp had a word for something, such as the menu entries and
  the window names, use the word that the Winamp translation into your language used, so
  the app reads the way people remember it. The table below has the Simplified Chinese
  ones.
- **Names stay.** Andamp, Winamp, Milkdrop, AVS, projectM, the names of skins, equalizer
  presets, plug-ins and effects are not translated.

## What stays in English

Some text is drawn with the bitmap letters of the skin, and a skin has letters only for
what Winamp wrote with them. That text cannot be translated: the song title in the main
window's scroller, the clock, the kbps and kHz readouts, and the title bars of the
windows. A playlist row in a script the skin has no letters for is drawn in a regular
font instead.

## Simplified Chinese: the words used

The last column says where a term comes from. "Winamp" is the Simplified Chinese text
Nullsoft wrote for the Winamp installer. "Audacious" is a free player with a Winamp-style
interface, used where the installer has no word. "Andamp" is a word for something Winamp
did not have.

| English | 简体中文 | From |
|---|---|---|
| skin | 面板 | Winamp |
| visualization | 可视化效果 | Winamp |
| Media Library | 媒体库 | Winamp |
| playlist | 播放列表 | Winamp |
| bookmark | 书签 | Winamp |
| preset | 预设 | Winamp |
| plug-in | 插件 | Winamp |
| Jump to file | 跳转到文件 | Winamp |
| Playlist Editor | 播放列表编辑器 | Audacious |
| Equalizer | 均衡器 | Audacious |
| Double Size | 双倍大小 | Audacious |
| Sort list, Reverse list, Randomize list | 排序列表, 反转列表, 打乱列表 | Audacious |
| Always On Top | 总在最前 | unverified |
| Preferences | 首选项 | unverified |
| Shuffle, Repeat | 随机播放, 重复播放 | unverified |
| music source | 音乐源 | Andamp |
| effect, rack | 效果, 机架 | Andamp |
| tip | 打赏 | Andamp |

"Unverified" means the word the Chinese Winamp menus used was not at hand. If you know
it, a pull request that corrects the table and the strings is welcome.
