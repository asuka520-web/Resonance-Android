# AGENTS.md — Resonance Android

## Project purpose
Resonance is a local-first Android companion for QQ Music. It observes QQ Music's Android MediaSession, records inferred listening behavior locally, and provides diagnostics and preliminary per-track preference summaries.

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
- Build (Windows): `.\gradlew.bat :app:assembleDebug`\n- Build (macOS/Linux): `./gradlew :app:assembleDebug`
- APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Non-negotiable data correctness rules
1. QQ Music's `MediaMetadata.METADATA_KEY_TITLE` may contain the currently displayed lyric line. Never treat `TITLE` alone as song identity, never use title changes as track-change detection, and never automatically save it as a verified song name.
2. Stable `METADATA_KEY_MEDIA_ID` is the current track identity signal. Do not regress to title-based detection.
3. `DISPLAY_TITLE`, `ARTIST`, `ALBUM`, `DISPLAY_SUBTITLE`, and `DISPLAY_DESCRIPTION` are diagnostic candidate fields only until device tests prove their semantics. Keep provenance and user confirmation explicit.
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
- Auto/recommend mode toggles are UI/settings only until actual QQ Music playback control is implemented. Do not imply that the app controls QQ Music today.
- All listening history and confirmed song metadata remain local unless the user explicitly opts into a future sync feature.

## Implementation quality
- Use Kotlin and Jetpack Compose; keep Android platform calls out of composables where practical.
- Keep media listener resilient to null sessions, paused playback, seeking, duplicate polling, and metadata updates.
- Do not count the same track transition more than once.
- Add unit tests for behavior classification, preference scoring, migrations, and metadata parsing when practical.
- Prefer clear UI states for "unknown", "candidate", and "user-confirmed" metadata.
- Do not add secrets, API keys, signing keys, or `local.properties` to Git.

## Release workflow
- Pushes to `main` trigger `.github/workflows/build-apk.yml`.
- CI should assemble the debug APK, upload a workflow artifact, and update the `latest-test` GitHub Release.
- Check the actual workflow run and release asset before telling the user a build or release succeeded.
