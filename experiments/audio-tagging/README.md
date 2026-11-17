# Local audio-tagging feasibility probe

This is a separate PC experiment, not an Android feature or a classification
accuracy report. It accepts an existing local 16 kHz mono PCM16 WAV, processes
3–30 seconds and writes predictions to a separate JSON report. It has no QQ
credentials, playback URL requests, account database imports or cache writes.
Inference is local. Setup downloads dependencies and the official model only.

## Run on Windows (Node 24)

```powershell
cd experiments/audio-tagging
./prepare.ps1
node probe.cjs --model .cache/msd-musicnn-1.onnx --metadata .cache/msd-musicnn-1.json --self-test --out .cache/smoke.json
node probe.cjs --model .cache/msd-musicnn-1.onnx --metadata .cache/msd-musicnn-1.json --wav C:/audio/clip.wav --seconds 30 --offset 0 --out .cache/clip-report.json
node --test probe.test.cjs
```

Convert other audio formats locally first. The probe deliberately rejects a
wrong sample rate/channel format instead of silently using incorrect features.
Silent input returns no tags. No audio is uploaded. No prediction is added to
the phone's verified genre data or used to drive automatic style changes.

## Evidence and limits

- Official MSD-MusiCNN v1 ONNX model, 3,168,334 bytes, pinned SHA256
  `49668ffec47e52e94b96f45930bb46a28a1368d4bdfb5c05378fa834aca616e1`.
- Uses Essentia's WASM `TensorflowInputMusiCNN` frontend through its public API,
  512-sample frames/256-sample hop, 187-frame patches/93-frame patch hop, then
  ONNX Runtime CPU inference. No copied preprocessing implementation.
- A six-second synthetic signal completed preprocessing and real model inference
  locally, with three patches and finite 50-tag outputs. This only validates the
  technical path. It proves no accuracy on the user's 37 unknown songs, Chinese
  music, emotion, audio decoding on Android or phone performance/battery use.
- Scores are uncalibrated model activations, not percentages of correctness.
  The 50 outputs mix genres, moods, voice, instruments and eras; they must not
  all be imported as genres. Human evaluation and separating evidence types are
  required before considering phone integration.
- Official TFJS ZIP was unusable with standard ZIP readers in this session;
  this probe uses the independently downloadable ONNX artifact instead.

Sources: [official models and license](https://essentia.upf.edu/models.html),
[model metadata](https://essentia.upf.edu/models/feature-extractors/musicnn/msd-musicnn-1.json),
[official frontend API](https://mtg.github.io/essentia.js/docs/api/EssentiaTFInputExtractor.html),
[official framing implementation](https://github.com/MTG/essentia/blob/master/src/algorithms/machinelearning/tensorflowpredictmusicnn.cpp).

The dependency Essentia.js is AGPL-3.0; ONNX Runtime is MIT. MTG model weights are
CC BY-NC-SA 4.0. Weights and dependencies are downloaded locally, ignored by Git,
and are not packaged with the Android app or the PC validator release. This
probe's source is offered under AGPL-3.0-or-later (see LICENSE).
