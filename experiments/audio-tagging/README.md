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

## Foreign-language recommendation pilot

The first ranking pilot prioritizes English. Its 33 JamendoLyrics tracks include
20 English, 5 French, 5 Spanish and 3 German recordings. The local metadata
manifest records the artist, coarse style tag, language, original track link and
track-specific license. Audio, model weights and votes stay outside the project.
[JamendoLyrics dataset](https://huggingface.co/datasets/jamendolyrics/jamendolyrics) ·
[Track license notes](https://huggingface.co/datasets/jamendolyrics/jamendolyrics/blob/2c7c7ce99d51fcc772d52d2f08b223df89dd9805/LICENSE).

To reproduce, copy `foreign-pilot-selection.csv` to a private working directory as
`selected.csv`. Save the MP3s from the same dataset's `mp3` directory using the
Jamendo track number in the URL as the local filename, such as `12345.mp3`.
Keep at least the first 55 seconds. `prepare-similarity.py` uses Python with
`miniaudio` installed. The model setup is the same as above.

```powershell
python prepare-similarity.py C:/audio/foreign-pilot
node similarity-pilot.cjs C:/audio/foreign-pilot/similarity-input.json .cache/msd-musicnn-1.onnx .cache/msd-musicnn-1.json
```

The experiment averages 200-dimensional MusiCNN features from two 15-second
windows starting at 15 and 40 seconds. The lightweight genre classifier averages
features within each catalogued genre and tests each artist against examples
from **other** artists. An artist whose genres have no remaining examples is
counted separately. The baseline predicts the most frequent available genre.
This is a broad-label check. The original MusiCNN weights are unchanged.

| Evaluation | Eligible tracks | Majority-style baseline | Audio classifier |
| --- | ---: | ---: | ---: |
| English only | 17 of 20 | 6 correct | 5 correct |
| Four languages together | 30 of 33 | 9 correct | 9 correct |

Three tracks in each evaluation had no same-genre example from a different
artist. This first classifier does not improve broad genre prediction in this
sample. The 11 blind comparisons (8 similarity, 3 direction) remain a separate
test of whether the audio features can improve candidate ranking.

The generated `blind-listening.html` compares two approaches on the same
eligible candidate sets. In similarity cases the tag-only approach picks among
same-genre songs in a fixed order, and the audio approach picks the closest.
In direction cases both filter out the common catalogue genre of four example
skips, and the audio approach then favors the lowest mean similarity to those
four. Same-artist candidates are excluded. The listening clips use basic signal
level adjustment for playback only. Answers remain local as an exported JSON
file. Listener votes are separate evidence from broad genre labels and the
experiment has no effect on Android ranking.

### Encoder comparison

The `--discogs` mode runs Essentia's 400-style Discogs-EffNet model and uses
its 1,280-value embedding. It reuses the same 16 kHz mel frontend and the same
recording excerpts, artist holdout and genre labels as the MusiCNN baseline.
It uses the model's native 128-frame patches with a 62-frame hop. The same-song
check searches for the second excerpt from the first against all second excerpts
without using genre labels. This checks consistency between different parts of a
song. The listening ballot remains the check for similarity between songs.
[Model catalogue](https://essentia.upf.edu/models.html#discogs-effnet) ·
[Official frontend and patch settings](https://essentia.upf.edu/reference/std_TensorflowPredictEffnetDiscogs.html).

```powershell
curl.exe -fL https://essentia.upf.edu/models/feature-extractors/discogs-effnet/discogs-effnet-bsdynamic-1.onnx -o .cache/discogs-effnet-bsdynamic-1.onnx
node similarity-pilot.cjs --discogs C:/audio/foreign-pilot/similarity-input.json .cache/discogs-effnet-bsdynamic-1.onnx
```

The comparison model is isolated to the PC experiment and verified by
checksum. It produces `similarity-report-discogs.json` and
`blind-listening-discogs.html` alongside the original pilot files.

| Same audio and test split | Most-common-genre baseline | MusiCNN | Discogs-EffNet |
| --- | ---: | ---: | ---: |
| Correct English genre | 6 / 17 | 5 / 17 | 7 / 17 |
| Correct genre across four languages | 9 / 30 | 9 / 30 | 6 / 30 |
| First-ranked second excerpt is the same recording | — | 27 / 33 | 29 / 33 |

Discogs-EffNet improved the small English check and the same-recording check.
The mixed-language genre check was weaker. It produced 8 similarity and 2
direction questions where the catalogue-only and audio rankings diverged.
Listener votes are pending, so these metrics support another English evaluation
and do not justify activating the model on Android.

### Trained clip ranking and per-song genre suggestions

`train-clips.cjs` fits a small logistic ranking model from the cosine scores of
MusiCNN and Discogs-EffNet. It uses the catalogue's broad genre tags as weak
supervision for pairs from different artists. For each English query artist,
all their tracks and excerpts are outside that fold's training data. The
evaluation searches other artists' recordings from one 15-second window at a
time and scores matching catalogue tags. The fitted model for all 33 recordings
stays in the pilot directory. The two audio encoders retain their original weights.

Genre suggestions use the Discogs-EffNet style outputs. For each song, the pilot
keeps up to three styles that appear in the top five for both excerpts, retaining
the model's style names and hierarchy. These are candidates for review. Scores
reflect the model's output, and coverage counts songs with a suggestion. Neither
measures fine-grained tag accuracy. The catalogue tag is stored separately.
[Official style classes](https://essentia.upf.edu/models/feature-extractors/discogs-effnet/discogs-effnet-bsdynamic-1.json).

```powershell
curl.exe -fL https://essentia.upf.edu/models/feature-extractors/discogs-effnet/discogs-effnet-bsdynamic-1.json -o .cache/discogs-effnet-bsdynamic-1.json
node train-clips.cjs C:/audio/foreign-pilot/similarity-input.json .cache/msd-musicnn-1.onnx .cache/msd-musicnn-1.json .cache/discogs-effnet-bsdynamic-1.onnx .cache/discogs-effnet-bsdynamic-1.json
```

The local outputs are `clip-ranking-model.json`, `clip-training-report.json`,
`model-genre-labels.html` and `blind-listening-trained.html`. They are part of the
PC pilot. Android's catalogue, genre scores and playback are separate.

The fitted ranking model used 2,108 pairs of excerpts from different artists,
244 of which shared the broad catalogue tag. Across 36 eligible English query
windows, the top pick shared that tag in 8 cases with MusiCNN, 14 with
Discogs-EffNet alone and 8 with the trained combination. Each had a matching
tag somewhere in the top three in 18 cases. Keep Discogs-EffNet as the ranking
baseline for further tests. This compares broad catalogue tags, while the
exported cases allow a separate listening judgment.

The song-level label pass retained at least one candidate on 30 of 33 songs.
For example, the catalogue labels `Give Me The Same` as Pop and the model
suggests Contemporary R&B, RnB/Swing and Downtempo. These describe separate
sources of evidence. Three tracks had no supported intersection across the two
clips, and the report left their suggestions empty.

### Independent English review

The next test uses MulJam v2.0 to find English vocals in each 15-second
window and MTG-Jamendo for catalogue genres and per-track licensing. The
selection targets ten recordings in each of eight broad genres, keeps one
recording per artist and excludes artists present in the first pilot. It
reuses Discogs-EffNet as-is. Its original training catalogue may overlap
these recordings.
[MulJam metadata](https://github.com/zhuole1025/LyricWhiz) ·
[MTG-Jamendo metadata, audio attribution and research-use terms](https://github.com/MTG/mtg-jamendo-dataset).

Place the LyricWhiz main branch archive (`lyricwhiz.zip`) and the three MTG
files (`data/raw_30s.tsv` as `mtg-tags.tsv`, `data/raw.meta.tsv` as
`mtg-meta.tsv` and `audio_licenses.txt` as `mtg-licenses.txt`) in the new
local review directory. Keep the first pilot's `selected.csv` as the source of
excluded artists. Then run from this experiment directory.

```powershell
python prepare-english-review.py C:/audio/english-review C:/audio/foreign-pilot
python prepare-similarity.py C:/audio/english-review
node english-review.cjs C:/audio/english-review/similarity-input.json C:/audio/foreign-pilot/discogs-effnet-bsdynamic-1.onnx C:/audio/foreign-pilot/discogs-effnet-bsdynamic-1.json
```

- `genre-review-english.html` plays both excerpts and records a judgment per
  suggested style, alongside songs where the model abstained. Measure the
  accepted share over all submitted judgments, with uncertain answers counted
  separately within that total. Record coverage separately across the full set.
- `blind-listening-english.html` compares the top catalogue-only pick against
  the closest first-clip match in the same genre and candidate pool, with
  different artists. The catalogue-only tie-breaker is track ID. Identical
  top picks are counted in the report and omitted from the ballot. The ballot
  measures preference among differing picks on this dataset, not performance
  of the full Android ranking.
- `english-review-report.json` records the full selection, coverage and case
  keys. Both pages save answers locally and export partial responses as JSON.
  Initial goals are 80% accepted suggestions and at least 30 audio wins in 50
  completed blind comparisons. Keep the audio, pages with embedded dataset
  metadata and votes local. This review does not update the app.

The first independent run analyzed 73 English recordings by 73 artists.
Discogs-EffNet produced at least one suggestion for 71 recordings. The
50-song audit contains 131 suggested labels. The two methods agreed on the
first pick for 12 of the 73 anchors and differed for 61. The ballot uses
50 of those differing cases. Human judgments are pending.

### Mobile model coverage and dedicated genre training

`mobile-genre-benchmark.py` uses the same YAMNet file, eligible genre labels,
five short slices per 15-second window, thresholds and agreement rule as Android.
It tests the first two windows and the later retry when enough audio is available.
The Windows Python run uses LiteRT, NumPy, scikit-learn and miniaudio. The source
audio is resampled locally to 16 kHz mono. This emulates the decision logic,
without measuring Android playback or battery behavior.

```powershell
node discogs-genre-features.cjs C:/audio/english-review C:/audio/foreign-pilot/discogs-effnet-bsdynamic-1.onnx
python mobile-genre-benchmark.py C:/audio/english-review C:/audio/yamnet.tflite --discogs-features C:/audio/english-review/discogs-genre-features.json
```

The Android rule yielded zero suggestions on 73 English tracks. Of 72 tracks
with enough data for a late retry, none produced a suggestion. Only five tracks
reached the genre threshold even once in the first two windows, and removing
the separate generic music gate produced no additional accepted tracks. A
regularized classifier trained on pooled YAMNet scores matched 22 of 73 broad
catalogue families when forced to choose. It could not meet the selective
validation target.

A separate classifier trained on Discogs-EffNet embeddings matched 35 of 73
families when forced to choose. With abstention, it returned 28 suggestions, 24
matching the catalogue family. Calibration used inner artist-disjoint folds
with a target of 80% agreement among emitted cases. The outer folds supplied
the reported comparison. In this small test, the gated embedding classifier
matched none of the suggested Pop tracks and abstained on R&B / Soul.
Catalogue-family agreement is a coarse proxy. Detailed label correctness and
listening preferences still need listening evaluation.

For a separate training cohort, reuse the review metadata, exclude the 73
review artists and first-pilot artists, and use the first roughly 55 seconds
of the public research recordings. The optional prefix download avoids
downloading the rest of the track. Failed or short files are skipped.

```powershell
python prepare-english-review.py C:/audio/genre-training C:/audio/foreign-pilot --metadata C:/audio/english-review --holdout C:/audio/english-review --per-genre 16 --prefix-bytes 1048576
python prepare-similarity.py C:/audio/genre-training
node discogs-genre-features.cjs C:/audio/genre-training C:/audio/foreign-pilot/discogs-effnet-bsdynamic-1.onnx
python mobile-genre-benchmark.py C:/audio/english-review C:/audio/yamnet.tflite --discogs-features C:/audio/english-review/discogs-genre-features.json --discogs-training C:/audio/genre-training
```

The larger run fits weights and chooses its threshold using the new cohort
only, then scores the original 73 songs. The original cohort influenced the
choice of model, so a final validation needs a fresh set. Features and
`mobile-genre-benchmark.json` remain in the local experiment directory.

Of 125 newly selected recordings, 123 decoded and supplied both windows.
Training on these 123 other artists made 13 high-confidence suggestions on the
original 73, of which 11 matched the primary catalogue family. Eight of the
13 suggestions were on tracks filed under Reggae. The larger cohort did not
solve Pop or R&B / Soul. A model trained with all catalogue genre families
instead of a single family did not reach the required calibration target on
that cohort. In the original review data, 4 of 10 tracks filed under Pop also
carry Rock-family tags, and 5 of 9 filed under R&B / Soul also carry Pop-family
tags. Learning these distinctions needs more representative, independently
checked examples, including mixed-genre recordings. No candidate from this
run was deployed to Android.

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
