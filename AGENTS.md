# AGENTS.md — Resonance Android

## 项目协作要求

开始任何项目任务前，先明确遵守以下要求。

1. 写简洁的代码，完成用户明确提出的目标。未经要求，不擅自判断、添加功能或修改验证规则。
2. 只做完成当前任务所必需的检查、验证、测试和验收，避免繁琐和重复的步骤。
3. 回复用清楚、好懂的话，让小孩子也能听明白。

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
The user subsequently reported 278 favourites and 241 tagged tracks, asked how
unknown genres and outside-favourite discovery work, and requested continued
feature work. v0.10.0 adds missing-genre state/retry, account-scoped direct QQ
relationships, automatic diverse seeds and actual recommendation queues. See
`docs/DISCOVERY_RELEASE_TEST.md`. Direct relationship recommendations may include
unknown genres only with explicit provenance; direction changes still require
known genres. Never turn these relationships into inferred genre labels.
The user then requested a browsable discovery section, complained that the
120-second direction cooldown is too long, and requested visible retry and
alternate-source genre buttons. v0.11.0 supersedes that cooldown for the native
player: default zero extra wait after each four valid skips, with optional
15/30/60/120 seconds. Keep the old external observer's 120-second fixture contract.
QQ errors as well as empty genre responses may fall back to strict MusicBrainz
matching; alternate-source retries must preserve positive cached tags and honor
the online-supplement setting. See `docs/DISCOVERY_BROWSER_TEST.md`.

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

## v0.12.0 user-requested work

The user resumed development after deferring list-tag layout. The requested
change is now included: complete tags underneath each song fit by actual width,
overflow hidden, all tags in a non-playing song-detail sheet. The user approved
optional unknown-genre surprise picks and a non-blocking fade notice on actual
automatic direction changes, then added playback modes/previous and multi-tag
wanted/excluded intent. See `docs/SURPRISE_PLAYER_TEST.md`.
Unknown surprise picks must retain their unknown evidence state, respect history
and familiarity, and never be used as proof of another genre. Active explicit
intent blocks unknown candidates. Direction notices require actual progress,
not just queue replacement. The separate PC audio probe only proves a local
inference path on synthetic input; it does not validate Chinese-song accuracy,
phone performance or create verified catalogue tags. Do not bundle model weights
or its research-only dependencies with the Android or desktop validator releases.

## v0.13.0 user-requested work

The user authorized hearts syncing QQ “我喜欢”, broader shuffle/loop scope and
surprise continuation, supplementing existing single-tag songs, exact playback
evidence and all-label score display. This supersedes the missing-only restriction
for supplementary queries: already positive songs may request strictly matched
recording genres, preserving QQ tags, provenance and old caches. Never invent tags.
Only explicit favourite actions may write to QQ; require fresh identity and server
read-back. Scope shuffle is native Media3 over selected cached catalogue metadata,
not QQ's private recommendation algorithm. Sparse four-skip evidence may replace a
batch, clearly labelled BATCH, never verified genre change. All tag scores use the
same feedback function as ranking; history stores at most 20 direction attempts per
account and never equates a request with audible playback. See docs/FEEDBACK_QUEUE_TEST.md.

## v0.14.0 user-requested work

Expose tag scores prominently and allow resetting all current-account scores;
retain history, favourite/genre data and familiarity. A separate capped scoring
stream defaults to legacy history until reset, then only new feedback contributes.
Reset also clears the in-memory fast-skip streak, temporary automatic avoidance,
pending confirmation and stale queued analysis without restarting current audio.
Profile shows device-wide native-player listening minutes, persisted separately
from feedback and the 500-event cap. Use monotonic audible intervals; never seek
positions or app-open duration. One-time legacy recovery is limited to retained
history; do not claim restoration of previously evicted events. See docs/SCORE_TIME_TEST.md.

## v0.15.0 user-requested work

The user selected restrained copy (相似推荐 / 随心听), approved more metadata and
active feedback, then explicitly requested dynamic weighted shuffle now if feasible
or a recorded TODO. Native shuffle is now weighted by the same displayed scores,
with bounded exponential weights, neutral unknowns and a stable visited prefix;
do not claim a fixed next track, QQ private algorithm or inferred emotions.
Active feedback is separate from actual listening events and QQ hearts, capped
per label, account-scoped and cleared by the existing score reset. Supplement
all eligible favourites instead of only 20 per process, retaining expiry and the
3-error pause. MusicBrainz non-genre recording descriptors use a controlled
allowlist and at least two votes, stored/displayed separately; they must not be
passed to genre matching, preference scores or automatic style proof. No new
audio model or additional provider is shipped. Pending evaluation and source
expansion must remain explicit in docs/ROADMAP.md. See docs/WEIGHTED_SHUFFLE_TEST.md.

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
- Four consecutive fast skips triggers analysis. Native-player cooldown defaults
  to zero extra wait and is configurable; the legacy observer retains 120 seconds.
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
