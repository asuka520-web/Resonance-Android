# Windows companion

The parent project's data-correctness and privacy rules apply. The Kotlin/Compose
requirement applies to the Android module; this companion uses Python, Tk and
PyWinRT with no changes to Android behavior.

- Windows GSMTC exposes candidate media fields, not Android MEDIA_ID. Do not
  invent a stable ID from TITLE or automatically count title changes as skips.
- Live recording requires a user-confirmed local song and explicit start/end.
- Simulation uses explicit fixture IDs and a separate data file. Never merge
  simulation history into the real library through the UI.
- Keep exported schemaVersion 3 / events / songLabels / songInfo compatible.
- Never overwrite a malformed data file. Writes must be atomic.
- Test thresholds, cooldown, duplicate polling, pauses, missing IDs, persistence,
  and Android JSON import before packaging.
