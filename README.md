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

## v0.6.2 test features

- Device-validated QQ Music `ARTIST` composites (`song name-artist`) are parsed and cached automatically after at least three observations spanning 2.4 seconds.
- Automatic metadata has explicit `qq-session-artist` provenance and separate storage; existing manual/legacy information always wins. Ambiguous formats remain manual, and same-ID identity changes invalidate automatic information.
- TITLE and DISPLAY_TITLE are never used for automatic identity or name caching. This does not implement genre classification, recommendation or target-song playback.

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
- **Media metadata diagnostics**: raw session fields, position, field change flags and automatic-reading status. Raw fields remain diagnostic; only the device-validated composite format can produce automatically cached metadata with explicit provenance.
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

- QQ Music may put live lyrics in `TITLE`. A non-empty field is not enough to authenticate a song name. v0.6.2 only auto-caches the stable `song name-artist` ARTIST format verified by the user on their device; unsupported, ambiguous or changing formats require manual confirmation. TITLE and DISPLAY_TITLE are not automatic name sources.
- No verified authorized QQ Music song-lookup / similarity / arbitrary playback API has been integrated. No true 70/30 recommendation queue or automatic replacement of QQ Music playback.
- Skip recognition is an estimate derived from playback position at track transition; repeat, seeking, paused or unusual sessions can cause imperfect classification.
- Current insights are based on **individual songs and observed behavior**, not actual genre or mood inference.
- Raw diagnostic lyric text is displayed in memory, not saved into song records or exported. History, manually confirmed information and parsed automatic metadata are stored on-device, with source preserved.

## APK

For existing installations signed by the old cloud workflow, use
[the side-by-side preview APK](https://github.com/asuka520-web/Resonance-Android/releases/download/latest-test/Resonance-preview-debug.apk)
to test without uninstalling the old app. It is labelled **Resonance 预览版**,
has separate data, and needs its own notification access. Old history is not
automatically migrated. The workflow now explicitly signs with a cached key;
the old workflow's nonexistent cache path did not preserve its generated keys.

[Latest test APK release](https://github.com/asuka520-web/Resonance-Android/releases/tag/latest-test)

GitHub Actions assembles the debug APK on pushes to `main`. The latest test release is updated after a successful build. Check [Actions](https://github.com/asuka520-web/Resonance-Android/actions) if the release has not updated yet.

For local building, use JDK 17 and Android SDK 35 with the included Gradle 8.9 Wrapper. On Windows, run `.\gradlew.bat :app:assembleDebug`; on macOS/Linux, run `./gradlew :app:assembleDebug`. No separate Gradle installation is required. See the [local development guide](docs/LOCAL_DEVELOPMENT.md) for setup and device checks.


## Local development with Codex

- Project rules: [`AGENTS.md`](AGENTS.md)
- Windows setup, Codex local mode, Android Studio, Gradle Wrapper and device test guide: [`docs/LOCAL_DEVELOPMENT.md`](docs/LOCAL_DEVELOPMENT.md)
