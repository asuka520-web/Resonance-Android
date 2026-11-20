# Local audio-tagging feasibility probe

This separate PC experiment measures tempo and signal level or predicts audio
tags from a 3–30 second excerpt of a local mono PCM16 WAV. It has no QQ
credentials, playback URL requests, account database imports or cache writes.
Analysis is local. Setup downloads dependencies and the official model only.

## Run on Windows (Node 24)

```powershell
cd experiments/audio-tagging
./prepare.ps1
node probe.cjs --model .cache/msd-musicnn-1.onnx --metadata .cache/msd-musicnn-1.json --self-test --out .cache/smoke.json
node probe.cjs --model .cache/msd-musicnn-1.onnx --metadata .cache/msd-musicnn-1.json --wav C:/audio/clip.wav --seconds 30 --offset 0 --out .cache/clip-report.json
node probe.cjs --measure-only --wav C:/audio/clip-44100.wav --seconds 15 --offset 15 --out .cache/measurement.json
node evaluate-tempo.cjs tempo-pilot.json C:/audio/pilot-wavs
node --test probe.test.cjs
```

Convert other audio formats locally first. The tag model uses 16 kHz. The tempo
algorithm uses 44.1 kHz and runs without the ONNX model. It leaves tempo unknown
for clips under 12 seconds, near silence, 5 or fewer detected beats, or tracker
confidence of 1.5 or less. A second estimator checks the first: within a ratio
of 1.05 the first estimate is kept, near a 2:1 ratio both candidates are reported
without selecting a BPM, and other disagreements are withheld. The benchmark
combines two excerpts only when at least one has an agreed estimate and the other
contains a matching estimate or candidate within 4%. `beatIntervalVariation` is the standard deviation of
beat intervals divided by their mean. `rmsDbfs` measures signal level in the
excerpt and varies with the mix/master. Silent input returns no tags. No audio
is uploaded and these results stay out of the app's genre data and ranking.
[Essentia beat tracker and confidence scale](https://essentia.upf.edu/reference/std_BeatTrackerMultiFeature.html),
[Percival cross-check](https://essentia.upf.edu/reference/std_PercivalBpmEstimator.html).

## First pilot: five recordings with one estimator

We measured two consecutive 15-second windows at offsets 0 and 15 seconds,
decoded to mono with pyminiaudio 1.61. The reference BPM is the composer's
catalogue value. All recordings are by Kevin MacLeod (Incompetech), used under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The downloaded MP3
excerpts and converted WAVs stayed local. [Official catalogue and downloads](https://incompetech.com/agent-section/).

| Recording | Catalogue BPM | Estimated BPM (0–15s / 15–30s) | Signal level, dBFS | Reference |
| --- | ---: | ---: | ---: | --- |
| Chill | 120 | 119.7 / 117.9 | −24.7 / −18.0 | [Track](https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1100375) |
| Sunshine | 88 | 88.1 / 88.0 | −15.4 / −14.8 | [Track](https://incompetech.com/music/royalty-free/index.html?isrc=USUAN1100517) |
| Morning | 60 | 118.8 / 120.0 | −21.0 / −20.3 | [Track](https://incompetech.com/music/royalty-free/index.html?isrc=USUAN2300003) |
| Southern Gothic | 126 | 125.7 / 126.1 | −34.0 / −32.1 | [Track](https://incompetech.com/music/royalty-free/index.html?isrc=USUAN2300004) |
| That Zen Moment | Undeclared | Unknown / Unknown | −28.7 / −24.5 | [Track](https://incompetech.com/music/royalty-free/index.html?isrc=USUAN2400001) |

Six of eight windows with a reference tempo were within 4%. The tracker
reported double tempo on both windows of *Morning* despite good confidence.
*Chill* gained 6.7 dB between windows while its tempo stayed steady, so rhythm
and signal level describe different qualities. Perceived energy still needs
listeners' ratings of loudness-matched excerpts.

The tag model ran on seconds 15–30 of every recording. Its acoustic and folk
tags for *Morning* fit the instrumentation, alongside a speculative `female
vocalists` tag. It ranked folk first for the author's Pop track *Sunshine*.
These recordings cover a single composer. Genre and emotion accuracy, real
Chinese recordings, mobile power usage and value for recommendations remain
open.

## Follow-up: cross-check and same-tempo ranking

The benchmark uses the five recordings above, five Chinese-inspired pieces from
PeriTune, and two Mandarin OpenCPOP performances in both dry and mixed versions.
Convert the public samples locally to mono 44.1 kHz PCM16 WAV. Use the file names
in `tempo-pilot.json` and keep the first 30 seconds, or the full file if shorter.
Audio files stay outside the repository.

| Additional recording | Reference BPM | Source and attribution |
| --- | ---: | --- |
| Chinatown / Chinatown (Healing) | 132 / 88 | PeriTune, CC BY 4.0. [Author download and BPM](https://peritune.com/blog/2017/06/22/chinatown/) |
| Folk_Chinese | 118 | PeriTune, CC BY 4.0. [Author download and BPM](https://peritune.com/blog/2018/01/14/folk_chinese/) |
| Wuxia | 136 | PeriTune, CC BY 4.0. [Author download and BPM](https://peritune.com/blog/2018/06/24/wuxia/) |
| Wuxia2 | 135 | PeriTune, CC BY 4.0. [Author download and BPM](https://peritune.com/blog/2018/10/13/wuxia2/) |
| 2092 dry / mixed and 2093 dry / mixed | No published reference in the demo | OpenCPOP team, local noncommercial evaluation. [Original samples](https://wenet-e2e.github.io/opencpop/) · [CC BY-NC-ND 4.0](https://wenet-e2e.github.io/opencpop/liscense/) |

Of 18 excerpts with reference BPM, the original estimator reported 18 values:
14 within 4% and 4 half/double-tempo errors. The cross-check reports 14 values:
12 within 4% and 2 double-tempo errors, with 4 withheld. Withholding removed
both 178 BPM readings for Chinatown (Healing), whose reference is 88 BPM, and
also removed one previously correct window each for Chill and Wuxia. Combining
two windows recovered the tempo for Chill and Wuxia. At recording level, 7 of 9
references were within 4%, Chinatown (Healing) stayed unknown, and Morning was
still estimated near 120 instead of 60 because both algorithms agreed on the
wrong pulse.

For OpenCPOP 2092, neither version produced a tempo estimate. In 2093, the first
window produced approximately 95 BPM in both versions, and the second did not
pass the confidence gate. The two-window rule left both recordings unknown.
The tag model ranked `female vocalists` first on all four versions, in line with
the corpus description. The subsequent genre order changed between dry and
mixed audio. These samples establish Mandarin input coverage for this pilot;
their tempo and genres have no independent annotations in the demo.

For the four eligible PeriTune anchors with the same broad tag, ranking by
measured tempo reduced the first pick's average difference from the reference
BPM from 8.75 to 4.75. Both cases used the same candidate set; the equal-tag
baseline used alphabetical ID order. Reference BPM was used only for scoring.
This comparison measures choosing a closer tempo on a small set. It does not
measure perceived similarity or user preference. A larger annotated collection,
listener comparisons and device power/playback measurements are still needed
before changing Android recommendations.

## Evidence and limits

- Official MSD-MusiCNN v1 ONNX model, 3,168,334 bytes, pinned SHA256
  `49668ffec47e52e94b96f45930bb46a28a1368d4bdfb5c05378fa834aca616e1`.
- Uses Essentia's WASM `TensorflowInputMusiCNN` frontend through its public API,
  512-sample frames/256-sample hop, 187-frame patches/93-frame patch hop, then
  ONNX Runtime CPU inference. No copied preprocessing implementation.
- A six-second synthetic signal completed preprocessing and model inference
  locally, with three patches and finite 50-tag outputs.
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
