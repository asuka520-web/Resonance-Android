# AGENTS.md — Resonance Android

## Project purpose

On 2026-11-17 the user explicitly chose **QQ Music account login and playback
inside Resonance, similar to MineRadio**. This supersedes the original external
QQ Music companion architecture. Android remains the product; PC is a test
workbench. Read `docs/ACCOUNT_PLAYER_DIRECTION.md` before further feature work.
The user confirmed the v0.8.0 real-account connection gate passed on their phone.
They then approved **Resonance-owned background playback of QQ sources** (not
remote control of the official QQ app), automatic favourite-first genre lookup,
supplementary MusicBrainz data hidden from the everyday flow, bounded streaming
cache and a redesigned UI. The user accepted this recommendation after discussing
data usage. See `docs/BACKGROUND_RELEASE_TEST.md` for v0.9.0 acceptance.

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
4. Identify the origin, access requirements and limitations of every QQ provider.
   Official SDK routes and third-party integrations must be labelled separately.
   MineRadio's open-source implementation may be inspected as a reference, not
   treated as an official mobile SDK or proof of current compatibility. Keep any
   third-party provider isolated and verify actual user-account catalogue and
   playback results. Do not bypass account authorization, membership, paywalls
   or protected content. Do not copy third-party code without its license terms.
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
- For the new catalogue, the user authorized automatic supplemental lookups.
  QQ's exact-MID single-song genre is primary. Only missing tags may use strict
  automatic MusicBrainz matching by name, primary artist, album and duration.
  No unique match means unknown; never use singer genres or free-form mood tags
  as track genres. Keep external-source disclosure/control in settings, never
  leak QQ cookies or listening history into external requests. Legacy manual
  lookup/export compatibility remains intact.
- Preserve `resonance_track_features` provenance and verified recording mapping.
  A changed song name, artist or album invalidates the old feature mapping.
- MusicBrainz is supplementary, not an audio provider or proof of QQ playback
  rights. Daily listening must not require per-track manual classification.
- The MediaSessionService owns playback/focus/queue, independently of Activity
  lifetime. Use opaque catalogue URIs, keep signed URLs out of exported metadata
  and cache indexes, scope audio cache keys by account, and cap audio cache at
  100 MiB. Never bulk-download favourites for feature extraction.
- Record explicit user transitions and actual audible elapsed time. Automatic
  replacements, paused/stale observations, seeks, failed loads and short previews
  must not manufacture dislikes or completed listening. Retain four-skip and
  120-second cooldown rules. Familiarity includes actual favourites; the 30%
  novel target must report availability honestly.
- Notification-session MEDIA_ID rules apply to the retained legacy observer.
  New catalogue song IDs/MIDs have their own provider namespace; never equate
  them to observed legacy IDs without an explicit verified mapping.
- Account login, readable playlists, usable playback authorization and actual
  player progress are separate states. Account cookies/keys must remain local
  and must never enter logs, exports, Git or GitHub Actions.
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
