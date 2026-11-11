# Contributing to Prism

Thanks for wanting to help. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](../../issues/new/choose) and pick **Bug report**. The most useful things to include:

- Prism version (Settings → About) and your Android version and phone
- Whether you're signed in
- What you did, what you expected, and what happened instead
- For playback or lyric problems: the song (a link is perfect)

Logs help a lot with playback problems. With USB debugging on, `adb logcat` while it happens and attach the part around the error. Please look through it first and remove anything personal.

## Building

You need JDK 17+ and the Android SDK (compile SDK 37). On Windows use `.\gradlew.bat`.

```bash
./gradlew :app:assembleDebug
```

To try your build without touching an installed Prism, build a side-by-side copy. It installs as `com.prism.music.dev` with its own data:

```bash
./gradlew :app:assembleDebug -PsideBySide
```

For a release build, the APK lands in `app/build/outputs/apk/release/`:

```bash
./gradlew :app:assembleRelease
```

Without a `keystore.properties` (and its keystore) in the project root, release builds are signed with the debug key so they install directly. Official releases use Prism's own key, so to install a self-built APK you first need to uninstall the official one. Release builds are deliberately unminified, because NewPipe Extractor and Rhino rely on reflection.

## Where things are

| Area | Where (`app/src/main/java/com/prism/music/`) |
|---|---|
| YouTube Music API, parsing and search ranking | `data/innertube/` |
| Stream resolving | `data/stream/` |
| Lyric providers and TTML / LRC / QRC / YRC / KRC parsers | `data/lyrics/` |
| Made for you, Blend and song recognition | `data/foryou/`, `data/blend/`, `data/recognize/` |
| Playback service, audio effects, Sing, DJ mode, Android Auto | `playback/` |
| Prism Connect (casting, browser remote, handoff) | `connect/` |
| Downloads and Smart Downloads | `download/` |
| Compose UI and design kit | `ui/` |

Stack: Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer and MediaSession, Room, DataStore, OkHttp, kotlinx.serialization, Coil, WorkManager, NewPipe Extractor, ONNX Runtime (downloaded on first use of Sing).

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Some tests call live YouTube Music and lyric endpoints, so they need a network connection and can occasionally fail when those services are slow or change their results. If one of those fails, run it again on its own before assuming your change broke it.

If you change a screen background, run `BackdropRenderTest` and look at the contact sheets it writes to `app/build/backdrop-previews/`.

## Pull requests

- Keep each pull request to one change, and say what it fixes or adds and how you checked it (which phone or emulator, light and dark mode for UI changes).
- Match the surrounding code: Kotlin, Jetpack Compose, the same naming and comment style. Dependencies are wired by hand in `AppContainer.kt` (no Hilt/Dagger).
- Don't enable R8/minification. NewPipe Extractor and Rhino rely on reflection.
- New libraries need a license compatible with GPL-3.0, and an entry in `ui/screens/LicensesPage.kt` (plus its license text in `app/src/main/assets/licenses/` if it isn't one already there).

## Translations

Prism's text lives in `app/src/main/res/values/strings.xml` (English) and one `values-xx/strings.xml` per language. The translations were made by Claude, an AI, so some words will read oddly. Corrections are very welcome:

- Edit that language's `strings.xml` and open a pull request. Change only the text between the tags, never the `name="…"`.
- Keep every placeholder (`%1$s`, `%2$s`, `%s`, `%%`) in the translation. You can move them around in the sentence. Write an apostrophe as `\'`.
- `TranslationsTest` checks each language against the English (nothing missing, the same placeholders). Run it with `./gradlew :app:testDebugUnitTest --tests '*TranslationsTest*'`. It's also run before your pull request is merged.
- To ask for a new language, open an issue.

## Ground rules

- **Privacy.** Prism has no server and no telemetry, and that's not going to change. Listening history, Replay and settings stay on the phone. Pull requests that add analytics, crash reporting services or tracking won't be merged.
- **Your YouTube session stays in Prism.** Never send Prism's YouTube cookies or account details to any third-party service.
- Prism is unofficial and not affiliated with Google or YouTube. Keep it that way in names, icons and wording.

## License

Prism is licensed under the [GNU General Public License v3.0 or later](LICENSE). By sending a contribution you agree that it's licensed the same way.
