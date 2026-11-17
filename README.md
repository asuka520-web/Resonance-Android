# Resonance for Android

A local-first QQ Music companion prototype, written in Kotlin + Jetpack Compose.

## Windows PC companion

**Android is the main product; PC is the validation workbench.** See the
[mobile-first testing strategy](docs/TESTING_STRATEGY.md).

Pushes to `main` build both the Android APK and the Windows PC validator.
[GitHub Actions](https://github.com/asuka520-web/Resonance-Android/actions)
contains build results; successful builds upload downloads to the
[latest test release](https://github.com/asuka520-web/Resonance-Android/releases/tag/latest-test).

A local Windows test companion is available in [`desktop/`](desktop/README.md).
It provides read-only Windows media-session diagnostics, manually confirmed
listening records, 20/500-event behavior profiles, Android JSON import/export,
and an isolated simulation mode. Windows does not expose Android's stable
MEDIA_ID through this API, so live recording requires explicit start/end;
metadata/title changes never automatically create listening events. PC testing
does not replace Android notification-permission and background-service tests.

## v0.6.1 test features

- Android listening rules now have JVM regression tests and a shared PC contract.
- Paused, disconnected, stale or identity-less observations do not infer skips.
- Per-song duration/progress cannot leak into the next song; the first four skips can trigger immediately.
- The history display and JSON export refresh even when the 500-event cap keeps category totals unchanged.
- PC can replay phone JSON read-only and compare stored categories with the rules.

- Background QQ Music MediaSession monitoring through Android notification access.
- Song transitions identified by stable `METADATA_KEY_MEDIA_ID`, **never by the changing `TITLE` field**.
- Listening behavior history (up to 500 events): fast skip (<15s), partial listen (<70%), mostly completed (>=70% or within 10s of the end).
- Trigger notice after 4 consecutive fast skips, with 120s cooldown.
- Verified, user-confirmed song labels and optional artist/album, cached on the device; v0.4/v0.5 manual labels remain readable.
- **Media metadata diagnostics**: `MEDIA_ID`, `TITLE`, `DISPLAY_TITLE`, `ARTIST`, `ALBUM`, display subtitle/description, play position and per-song field change flags. These are diagnostic raw fields, NOT confirmed song identity.
- Separate behavior scores for the **latest 20 events** and **latest 500 events**. Completed +2 / partial -1 / fast skip -3.
- Latest 10 history items, local JSON export (v3 includes songInfo), mode switch persistence.

## One-round manual test

1. Allow notification access, play one song for at least 20-30 seconds and open **媒体元数据诊断**.
2. While QQ Music lyrics change, check whether TITLE and DISPLAY_TITLE change; verify MEDIA_ID remains stable. Note which fields reliably contain a real name/artist/album (they may be empty).
3. Switch to another song and confirm its MEDIA_ID changes, with no lyric-based fake skips.
4. Confirm a song name/artist/album, switch away and back; ensure the manually verified information is restored.
5. Produce a few fast skips and longer listens; compare latest 20 and 500 listening profiles, then check behavior history.
6. Export JSON and verify `schemaVersion=3`, `events`, `songLabels`, and `songInfo`.
7. Background the app, play music, return to the app and verify that recent events were recorded.

## Known limitations

- QQ Music may put live lyrics in `TITLE` (possibly other display fields as well). A non-empty metadata field is **not enough** to authenticate a song name. v0.6 exposes candidate information for manual inspection but deliberately does **not** auto-commit it to the cache.
- No verified authorized QQ Music song-lookup / similarity / arbitrary playback API has been integrated. No true 70/30 recommendation queue or automatic replacement of QQ Music playback.
- Skip recognition is an estimate derived from playback position at track transition; repeat, seeking, paused or unusual sessions can cause imperfect classification.
- Current insights are based on **individual songs and observed behavior**, not actual genre or mood inference.
- Raw diagnostic lyric text is displayed in memory, not saved into song records or exported. History and confirmed song information are stored on-device.

## APK

[Latest test APK release](https://github.com/asuka520-web/Resonance-Android/releases/tag/latest-test)

GitHub Actions assembles the debug APK on pushes to `main`. The latest test release is updated after a successful build. Check [Actions](https://github.com/asuka520-web/Resonance-Android/actions) if the release has not updated yet.

For local building, use JDK 17 and Android SDK 35 with the included Gradle 8.9 Wrapper. On Windows, run `.\gradlew.bat :app:assembleDebug`; on macOS/Linux, run `./gradlew :app:assembleDebug`. No separate Gradle installation is required. See the [local development guide](docs/LOCAL_DEVELOPMENT.md) for setup and device checks.


## Local development with Codex

- Project rules: [`AGENTS.md`](AGENTS.md)
- Windows setup, Codex local mode, Android Studio, Gradle Wrapper and device test guide: [`docs/LOCAL_DEVELOPMENT.md`](docs/LOCAL_DEVELOPMENT.md)
