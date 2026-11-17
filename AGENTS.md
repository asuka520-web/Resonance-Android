# AGENTS.md — Resonance Android

## Project purpose

Core requirements and acceptance gates: `docs/PRODUCT_REQUIREMENTS.md`.
The main outcomes are skip-triggered style switching and current-intent similar
song recommendations on Android. Artist statistics, local scores, PC tools and
test counts are supporting work, not completion of these outcomes. Verify
catalogue/features and target-song playback feasibility before expanding UI.
Resonance is a local-first Android companion for QQ Music. v0.7.0 adds opt-in
MusicBrainz recording-genre lookup, genre-based recommendations over locally
identified songs, and public MediaSession target-play requests with confirmation.
QQ target playback remains device-unverified until tested on the user's phone.

Android is the primary product. `desktop/` is a validation companion, not a
second product roadmap. Keep behavior contracts in `test-fixtures/` and execute
them against both Android's Kotlin rules and the PC validation implementation.
Desktop event replay is read-only and does not reproduce Android permissions or
notification-service lifecycle. See `docs/TESTING_STRATEGY.md`.

## Before editing
- Read `README.md` and `docs/LOCAL_DEVELOPMENT.md`.
- Inspect the current implementation before changing it. Do not assume old chat summaries describe the current source.
- Keep changes focused and explain any behavior changes.
- Check `git status` before and after work. Never discard user changes.

## Build environment
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.0.21
- Gradle: 8.9
- JDK: 17
- compileSdk / target SDK: 35
- minSdk: 26
- Main module: `:app`
- Build (Windows): `.\gradlew.bat :app:assembleDebug`
- Build (macOS/Linux): `./gradlew :app:assembleDebug`
- APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Non-negotiable data correctness rules
1. QQ Music's `MediaMetadata.METADATA_KEY_TITLE` may contain the currently displayed lyric line. Never treat `TITLE` alone as song identity, never use title changes as track-change detection, and never automatically save it as a verified song name.
2. Stable `METADATA_KEY_MEDIA_ID` is the current track identity signal. Do not regress to title-based detection.
3. `DISPLAY_TITLE`, `ARTIST`, `ALBUM`, `DISPLAY_SUBTITLE`, and `DISPLAY_DESCRIPTION` are diagnostic candidate fields only until device tests prove their semantics. Keep provenance and user confirmation explicit.
   On 2026-11-17 the user verified across multiple tracks that their QQ Music
   supplies a stable `song name-artist` composite in ARTIST and album in ALBUM.
   v0.6.2 may cache this narrowly parsed format after stable observations, under
   `qq-session-artist` provenance in `resonance_auto_song_info`. Preserve manual
   and legacy labels first; ambiguous or changing fields must not auto-commit.
4. Do not integrate undocumented, unauthorized, brittle QQ Music endpoints or scrape private APIs. A lookup source must be documented, permitted, and tested before use.
5. Never claim recommendation or automatic playback works until an actual supported catalogue/search/playback integration is implemented and tested.

## Preserve existing user data
- Existing SharedPreferences keys are user data:
  - `resonance_history` / `events`
  - `resonance_song_labels`
  - `resonance_settings` / `auto`
  - `resonance_song_info`
- Maintain backward compatibility with v0.4/v0.5 manual song labels and JSON exports.
- Do not clear preferences or change schema destructively. Add explicit migrations and tests if a schema change is necessary.
- History is capped at 500 events. Keep the cap unless intentionally redesigned with migration/export.

## Product behavior constraints
- Four consecutive fast skips triggers analysis; cooldown is 120 seconds.
- Current behavior scoring is heuristic: completed +2, partial -1, fast skip -3. Label it as a heuristic, not AI or genre understanding.
- Current short-term view uses latest 20 events; long-term view uses latest 500 events.
- Legacy `resonance_settings/auto` is retained but must not enable playback.
  Actual automatic control uses the separate default-off
  `style_auto_control_enabled` opt-in. Only try one supported target per
  four-skip analysis; confirm its exact MEDIA_ID playing across two polls.
  Capability flags or a successful request call alone are not playback success.
- MusicBrainz lookups require an explicit query/recording selection and send
  only that song's name/artist or the selected public recording UUID. Never
  upload listening history or infer track genres from artist genres/free tags.
- Preserve `resonance_track_features` provenance and verified recording mapping.
  A changed song name, artist or album invalidates the old feature mapping.
- All listening history and confirmed song metadata remain local unless the user explicitly opts into a future sync feature.

## Implementation quality
- Use Kotlin and Jetpack Compose; keep Android platform calls out of composables where practical.
- Keep media listener resilient to null sessions, paused playback, seeking, duplicate polling, and metadata updates.
- Do not count the same track transition more than once.
- Add unit tests for behavior classification, preference scoring, migrations, and metadata parsing when practical.
- Prefer clear UI states for "unknown", "candidate", and "user-confirmed" metadata.
- Do not add secrets, API keys, signing keys, or `local.properties` to Git.

## Release workflow

- All local/cloud debug, preview and release APKs use the pinned existing Android
  signing identity in `signing-certificate.sha256`. Missing or mismatched keys
  must fail packaging, never generate a fallback key. Cloud restores the key
  from `RESONANCE_ANDROID_KEYSTORE_BASE64`; local uses ignored `.ci-signing/`.
  Never commit signing keys, encrypted secret payloads or transfer private keys.
- Pushes to `main` trigger `.github/workflows/build-apk.yml`.
- CI should assemble the debug APK, upload a workflow artifact, and update the `latest-test` GitHub Release.
- Check the actual workflow run and release asset before telling the user a build or release succeeded.
