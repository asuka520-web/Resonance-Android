# Resonance for Android

A local-first Android music assistant with QQ account integration, background
playback, favourite-first genre enrichment and recommendations. Kotlin + Compose.

## v0.11.0

- Browse the outside-favourite pool in a dedicated Discovery tab, with source
  explanations, search, genre/local-history filters and exact visible-order queues.
- Avoid recent negative feedback and known recording duplicates; use real recent
  positive genre evidence and spread artists rather than displaying raw fetch order.
- Native-player direction changes default to no extra wait after each four valid
  skips; optional 15/30/60/120-second waits take effect on the next transition.
- Visible retry and alternate-source buttons can schedule missing songs while
  initial analysis is running. QQ errors or empty tags may use strict supplementary
  matching. Existing tags survive failed refreshes; opt-out and cancellation honored.
- [Phone acceptance](docs/DISCOVERY_BROWSER_TEST.md). Fixed signing and old data preserved.

## v0.10.0

- Separate waiting, failed/retryable and unavailable genre states, with a batch
  retry that preserves existing positive tags and reads legacy cached data.
- Automatically discover QQ related-song candidates from diverse favourite
  seeds and the current track. Cache direct relationships per account, expose
  outside-favourite counts and a discover-more action; fetch metadata only.
- Unknown genres may use explicit QQ direct relations for similar recommendations,
  labelled separately from genre evidence. Direction changes still require known
  genres. No reverse/transitive relationship or artist-genre inference.
- Play a recommendation batch in its actual order, including starting at a
  selected song; never append the old favourites order to that batch.
- [Phone acceptance](docs/DISCOVERY_RELEASE_TEST.md). Existing fixed signing,
  histories, account login and bounded background playback remain intact.

## v0.9.0

- Independent Media3 session service with background audio, system controls and
  a bounded 100 MiB account-scoped audio cache. Audio goes directly from QQ to
  the phone; no relay server or bulk favourite-audio download.
- Automatic paged favourite sync and exact-MID QQ song-genre lookup. Missing
  genres may use strict supplementary MusicBrainz matching, with disclosure and
  control in settings rather than the daily listening flow.
- Similar-mode recommendations combine favourites and actual QQ related-song
  candidates. Four valid fast skips can replace the queue with another genre
  direction, retaining 120-second cooldown and explainable unavailable states.
- Direct listening events count actual heard time; app-driven changes, seeks,
  errors and previews do not manufacture user feedback.
- Redesigned For You / Favourites / Profile, mini-player, artwork, player sheet,
  dark theme and adaptive icon. Legacy history remains available in settings.

The user verified v0.8.0 account/catalogue/native playback on their phone. New
background/system-control behaviour still needs the
[v0.9.0 phone acceptance checklist](docs/BACKGROUND_RELEASE_TEST.md). Genre tags
are not an acoustic/mood model; coverage and novel ratios are reported honestly.

**Development direction, 2026-11-17:** the user chose QQ Music account login and
playback **inside Resonance**, and confirmed the v0.8.0 account/catalogue/playback
gate passed. v0.9.0 extends that connection into background listening and
automatic recommendations. Account presence, readable library, playback tickets
and actual player progress remain separate checks. No account credentials enter
CI. See [the findings and acceptance history](docs/ACCOUNT_PLAYER_DIRECTION.md).

## Legacy v0.7.0: external-player recommendations and target-play trial

- Explicit MusicBrainz queries retrieve **recording-level genres** after the user checks the exact version. Missing genres stay unknown. Only selected name/artist or public recording UUID are sent; no history or lyrics are uploaded.
- “我想继续听这类歌” recommends other locally identified songs with overlapping verified genres, using recent/long-term positive signals and repeat penalties. This is genre similarity, not acoustic/mood/tempo recognition.
- Four consecutive fast skips trigger a different-direction plan, retaining the 120-second cooldown. All four rejected tracks need verified genres and a label shared by at least three; mixed/unknown styles do not become fabricated intent.
- An available-candidate 70/30 familiar/exploration mix reports actual counts. Exploration means no **local event history**, not guaranteed globally unheard music.
- Public QQ MediaSession queue/action inspection, target-play requests and exact-ID playback confirmation are implemented. Confirmation requires the target playing across two polls; requests time out after 10 seconds without retry loops. A **new default-off** setting permits one automatic target per eligible skip analysis. Legacy mode settings do not activate control.
- **QQ target playback is not yet verified on the user's phone.** Recommendations cover the local identified/tagged library; no full QQ catalogue or audio model is connected.

[Phone acceptance steps](docs/CORE_FEATURE_TEST.md) · [Core requirements and remaining work](docs/PRODUCT_REQUIREMENTS.md)

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

## Retained identification and listening features

- Device-validated QQ Music `ARTIST` composites (`song name-artist`) are parsed and cached automatically after at least three observations spanning 2.4 seconds.
- Automatic metadata has explicit `qq-session-artist` provenance and separate storage; existing manual/legacy information always wins. Ambiguous formats remain manual, and same-ID identity changes invalidate automatic information.
- TITLE and DISPLAY_TITLE are never used for automatic identity or name caching.

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
- Latest 10 history items, compatible local JSON export (v3 with songInfo and optional trackFeatures).

## One-round manual test

1. Allow notification access, play one song for at least 20-30 seconds and open **媒体元数据诊断**.
2. While QQ Music lyrics change, check whether TITLE and DISPLAY_TITLE change; verify MEDIA_ID remains stable. Note which fields reliably contain a real name/artist/album (they may be empty).
3. Switch to another song and confirm its MEDIA_ID changes, with no lyric-based fake skips.
4. Confirm a song name/artist/album, switch away and back; ensure the manually verified information is restored.
5. Produce a few fast skips and longer listens; compare latest 20 and 500 listening profiles, then check behavior history.
6. Export JSON and verify `schemaVersion=3`, `events`, `songLabels`, `songInfo` and optional `trackFeatures`.
7. Background the app, play music, return to the app and verify that recent events were recorded.

## Known limitations

- QQ Music may put live lyrics in `TITLE`. A non-empty field is not enough to authenticate a song name. v0.6.2 only auto-caches the stable `song name-artist` ARTIST format verified by the user on their device; unsupported, ambiguous or changing formats require manual confirmation. TITLE and DISPLAY_TITLE are not automatic name sources.
- No full QQ catalogue integration or verified arbitrary playback endpoint is available. Public Android transport requests may be unsupported or ignored by QQ Music; capability declarations alone do not establish success. See the phone tests above.
- Skip recognition is an estimate derived from playback position at track transition; repeat, seeking, paused or unusual sessions can cause imperfect classification.
- Behavior scores remain heuristics. Genre recommendations use explicitly matched community recording tags with incomplete coverage; mood, tempo, audio embeddings and acoustic classification remain unimplemented.
- Raw diagnostic lyric text is displayed in memory, not saved into song records or exported. History, confirmed metadata and verified recording-genre mappings remain on-device; explicitly requested public lookups send only selected query terms.

## APK

For existing installations signed by the old cloud workflow, use
[the side-by-side preview APK](https://github.com/asuka520-web/Resonance-Android/releases/download/latest-test/Resonance-preview-debug.apk)
to test without uninstalling the old app. It is labelled **Resonance 预览版**,
has separate data, and needs its own notification access. Old history is not
automatically migrated. All future local/cloud APKs use the same pinned signing
key, restored from an encrypted GitHub Secret rather than an evictable cache.
Missing or mismatched keys fail packaging. See [signing setup](docs/SIGNING.md).
The old workflow's nonexistent cache path did not preserve its early keys.

[Latest test APK release](https://github.com/asuka520-web/Resonance-Android/releases/tag/latest-test)

GitHub Actions assembles the debug APK on pushes to `main`. The latest test release is updated after a successful build. Check [Actions](https://github.com/asuka520-web/Resonance-Android/actions) if the release has not updated yet.

For local building, use JDK 17 and Android SDK 35 with the included Gradle 8.9 Wrapper. On Windows, run `.\gradlew.bat :app:assembleDebug`; on macOS/Linux, run `./gradlew :app:assembleDebug`. No separate Gradle installation is required. See the [local development guide](docs/LOCAL_DEVELOPMENT.md) for setup and device checks.


## Local development with Codex

- Project rules: [`AGENTS.md`](AGENTS.md)
- Windows setup, Codex local mode, Android Studio, Gradle Wrapper and device test guide: [`docs/LOCAL_DEVELOPMENT.md`](docs/LOCAL_DEVELOPMENT.md)
