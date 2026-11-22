"""Reproduce the Android YAMNet rules and test a small genre classifier locally."""

import argparse
import csv
import hashlib
import json
import re
import runpy
from collections import Counter
from pathlib import Path

import miniaudio
import numpy as np
from ai_edge_litert.interpreter import Interpreter
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedGroupKFold, cross_val_predict
from sklearn.multiclass import OneVsRestClassifier
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler


RATE = 16_000
INPUT = 15_600
SLICES = (1, 4, 7, 10, 13)
MODEL_SHA = "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"
FAMILIES = {
    "流行": "Pop", "嘻哈": "Hip-Hop", "摇滚": "Rock", "金属": "Rock", "朋克摇滚": "Rock",
    "R&B": "R&B / Soul", "灵魂乐": "R&B / Soul", "雷鬼": "Reggae", "民谣": "Folk",
    **dict.fromkeys(("电子", "House", "Techno", "Dubstep", "Drum and Bass", "Electronica",
                     "氛围音乐", "Trance"), "Electronic"),
}


def android_indices():
    source = (Path(__file__).resolve().parents[2] /
              "app/src/main/java/com/resonance/app/ModelGenres.kt").read_text(encoding="utf-8")
    table = source.split("private val genreIndices = linkedMapOf(", 1)[1].split(")", 1)[0]
    return {int(index): label for index, label in re.findall(r'(\d+) to "([^"]+)"', table)}


def assess(first, second, indices, music_gate=True):
    def strengths(windows):
        result = {}
        for index in indices:
            eligible = windows[:, index] >= .4
            if music_gate:
                eligible &= windows[:, 132] >= .5
            if eligible.sum() >= 3:
                result[index] = float(windows[eligible, index].mean())
        return result

    left, right = strengths(first), strengths(second)
    if not left or not right:
        return None, "insufficient"
    shared = left.keys() & right.keys()
    if not shared:
        return None, "different"
    best = max(shared, key=lambda index: (min(left[index], right[index]), -list(indices).index(index)))
    return indices[best], "agreement"


def clips(interpreter, directory, track):
    decoder = miniaudio.decode_file(str(directory / f"{track['id']}.mp3"),
                                   output_format=miniaudio.SampleFormat.SIGNED16, nchannels=1, sample_rate=RATE)
    audio = np.frombuffer(decoder.samples, dtype=np.int16)
    input_index = interpreter.get_input_details()[0]["index"]
    output_index = interpreter.get_output_details()[0]["index"]
    output = {}
    for start in (15, 40, 60, 80):
        if len(audio) < (start + 15) * RATE:
            continue
        windows = []
        for second in SLICES:
            begin = (start + second) * RATE
            wave = audio[begin:begin + INPUT].astype(np.float32) / 32768
            interpreter.set_tensor(input_index, wave)
            interpreter.invoke()
            windows.append(interpreter.get_tensor(output_index)[0])
        output[start] = np.stack(windows)
    return output


def metrics(expected, predicted):
    emitted = sum(value is not None for value in predicted)
    correct = sum(left == right for left, right in zip(expected, predicted) if right is not None)
    return {"tested": len(expected), "emitted": emitted, "matchingCatalogueFamily": correct,
            "matchAmongEmitted": round(correct / emitted, 4) if emitted else None}


def model():
    return make_pipeline(StandardScaler(), LogisticRegression(C=.15, max_iter=500, class_weight="balanced"))


def calibrate(features, expected, artists):
    labels = np.unique(expected)
    splits = StratifiedGroupKFold(n_splits=3, shuffle=True, random_state=521)
    scores = cross_val_predict(model(), features, expected, groups=artists, cv=splits, method="predict_proba")
    guesses = labels[scores.argmax(axis=1)]
    confidence = scores.max(axis=1)
    for threshold in np.arange(.35, .951, .05):
        keep = confidence >= threshold
        if keep.sum() >= 8 and np.mean(guesses[keep] == expected[keep]) >= .8:
            return float(threshold)
    return None


def fit(features, expected, artists):
    top = [None] * len(expected)
    selective = [None] * len(expected)
    choices = []
    outer = StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=520)
    for train, test in outer.split(features, expected, artists):
        train_x, train_y, train_artists = features[train], expected[train], artists[train]
        cutoff = calibrate(train_x, train_y, train_artists)
        choices.append(round(cutoff, 2) if cutoff else None)
        trained = model().fit(train_x, train_y)
        scores = trained.predict_proba(features[test])
        for index, row in zip(test, scores):
            label = trained.classes_[row.argmax()]
            top[index] = label
            if cutoff is not None and float(row.max()) >= cutoff:
                selective[index] = label
    return top, selective, choices


def by_family(expected, selected):
    return {label: metrics(expected[expected == label],
                           [value for actual, value in zip(expected, selected) if actual == label])
            for label in np.unique(expected)}


def external_fit(directory, field, test_features, expected, test_artists):
    source = json.loads((directory / "discogs-genre-features.json").read_text(encoding="utf-8"))
    manifest = json.loads((directory / "similarity-input.json").read_text(encoding="utf-8"))
    rows = [row for row in manifest if row["id"] in source]
    train_x = np.array([source[row["id"]][field] for row in rows])
    train_y = np.array([row["tag"] for row in rows])
    artists = np.array([row["artist"] for row in rows])
    normalize = lambda name: "".join(char for char in name.casefold() if char.isalnum())
    if {normalize(artist) for artist in artists} & {normalize(artist) for artist in test_artists}:
        raise ValueError("Training and test sets must not share artists")
    if set(train_y) != set(expected) or min(Counter(train_y).values()) < 3:
        raise ValueError("Need at least three training artists per tested genre")
    cutoff = calibrate(train_x, train_y, artists)
    trained = model().fit(train_x, train_y)
    scores = trained.predict_proba(test_features)
    top = trained.classes_[scores.argmax(axis=1)]
    accepted = [label if cutoff is not None and score >= cutoff else None
                for label, score in zip(top, scores.max(axis=1))]
    return {
        "trainingTracks": len(rows), "trainingArtists": len(set(artists)), "trainingByFamily": dict(Counter(train_y)),
        "threshold": round(cutoff, 2) if cutoff else None,
        "top": metrics(expected, top), "selective": metrics(expected, accepted),
        "byFamily": by_family(expected, accepted),
    }, top, accepted


def catalogue_tags(directory, classes):
    families = runpy.run_path(str(Path(__file__).with_name("prepare-english-review.py")))["GENRES"]
    with (directory / "selected.csv").open(encoding="utf-8-sig", newline="") as source:
        return {Path(row["Filepath"]).name.removesuffix(".low.mp3"):
                [bool(families[label].intersection(row["Tags"].split("|"))) for label in classes]
                for row in csv.DictReader(source)}


def multilabel_fit(training, review, rows, test_features, expected):
    train_manifest = json.loads((training / "similarity-input.json").read_text(encoding="utf-8"))
    features = json.loads((training / "discogs-genre-features.json").read_text(encoding="utf-8"))
    train_rows = [row for row in train_manifest if row["id"] in features]
    classes = np.unique(expected)
    training_tags = catalogue_tags(training, classes)
    review_tags = catalogue_tags(review, classes)
    train_x = np.array([features[row["id"]]["embedding"] for row in train_rows])
    train_y = np.array([training_tags[row["id"]] for row in train_rows])
    review_y = np.array([review_tags[row["id"]] for row in rows])
    groups = np.array([row["artist"] for row in train_rows])
    split_labels = [row["tag"] for row in train_rows]
    inner = list(StratifiedGroupKFold(n_splits=3, shuffle=True, random_state=521).split(train_x, split_labels, groups))
    classifier = make_pipeline(StandardScaler(), OneVsRestClassifier(LogisticRegression(C=.15, max_iter=500, class_weight="balanced")))
    scores = cross_val_predict(classifier, train_x, train_y, cv=inner, method="predict_proba")
    top = scores.argmax(axis=1)
    cutoff = None
    for threshold in np.arange(.35, .951, .05):
        keep = scores.max(axis=1) >= threshold
        if keep.sum() >= 8 and train_y[np.arange(len(top)), top][keep].mean() >= .8:
            cutoff = float(threshold)
            break
    scores = classifier.fit(train_x, train_y).predict_proba(test_features)
    top = scores.argmax(axis=1)
    selected = scores.max(axis=1) >= cutoff if cutoff is not None else np.zeros(len(rows), dtype=bool)
    matched = review_y[np.arange(len(top)), top]
    return {
        "threshold": round(cutoff, 2) if cutoff else None,
        "emitted": int(selected.sum()),
        "matchingAnyCatalogueFamily": int((matched & selected).sum()),
        "sameSingleCatalogueTarget": metrics(expected, [classes[i] if keep else None for i, keep in zip(top, selected)]),
        "byFamily": {label: {"emitted": int((selected & (expected == label)).sum()),
                             "matchingAnyCatalogueFamily": int((selected & matched & (expected == label)).sum())}
                     for label in classes},
    }


def main(args):
    directory = args.directory
    if hashlib.sha256(args.model.read_bytes()).hexdigest() != MODEL_SHA:
        raise ValueError("Expected the exact Android YAMNet model")
    manifest = json.loads((directory / "similarity-input.json").read_text(encoding="utf-8"))
    indices = android_indices()
    interpreter = Interpreter(model_path=str(args.model), num_threads=1)
    interpreter.allocate_tensors()
    rows, features = [], []
    for track in manifest:
        scores = clips(interpreter, directory, track)
        if 15 not in scores or 40 not in scores:
            continue
        first, reason = assess(scores[15], scores[40], indices)
        late, late_reason = (assess(scores[60], scores[80], indices)
                             if first is None and 60 in scores and 80 in scores else (None, "not_needed_or_unavailable"))
        without_gate, _ = assess(scores[15], scores[40], indices, music_gate=False)
        pooled = np.concatenate([scores[15], scores[40]])[:, 132:271]
        features.append(np.concatenate([pooled.mean(axis=0), pooled.std(axis=0)]))
        rows.append({"id": track["id"], "artist": track["artist"], "title": track["title"], "catalogueFamily": track["tag"],
                     "first": first, "firstStatus": reason, "late": late, "lateStatus": late_reason,
                     "noMusicGate": without_gate,
                     "peakGenreScore": float(max(scores[15][:, list(indices)].max(), scores[40][:, list(indices)].max()))})
        if len(rows) % 10 == 0:
            print(f"Analyzed {len(rows)} tracks", flush=True)
    expected = np.array([row["catalogueFamily"] for row in rows])
    artists = np.array([row["artist"] for row in rows])
    top, selective, cutoffs = fit(np.stack(features), expected, artists)
    for row, prediction, abstaining in zip(rows, top, selective):
        row["trainedTopFamily"] = prediction
        row["trainedSelectiveFamily"] = abstaining
    def map_label(label):
        return FAMILIES.get(label, "Other") if label else None
    report = {
        "dataset": "Independent English review", "tracks": len(rows), "artists": len(set(artists)),
        "genreCounts": dict(Counter(expected)), "firstStatuses": dict(Counter(row["firstStatus"] for row in rows)),
        "lateStatuses": dict(Counter(row["lateStatus"] for row in rows)),
        "androidFirst": metrics(expected, [map_label(row["first"]) for row in rows]),
        "androidWithLater": metrics(expected, [map_label(row["first"] or row["late"]) for row in rows]),
        "diagnosticFirstWithoutGenericMusicGate": metrics(expected, [map_label(row["noMusicGate"]) for row in rows]),
        "trainedTop": metrics(expected, top), "trainedSelective": metrics(expected, selective),
        "innerThresholds": cutoffs,
        "firstTwoWindowsEverReachGenreThreshold": sum(row["peakGenreScore"] >= .4 for row in rows),
        "rows": rows,
    }
    if args.discogs_features:
        alternatives = json.loads(args.discogs_features.read_text(encoding="utf-8"))
        for field in ("embedding", "styles"):
            vectors = np.array([alternatives[row["id"]][field] for row in rows])
            guessed, accepted, thresholds = fit(vectors, expected, artists)
            report["discogs" + field.capitalize()] = {
                "top": metrics(expected, guessed), "selective": metrics(expected, accepted),
                "innerThresholds": thresholds,
                "byFamily": by_family(expected, accepted),
            }
            for row, prediction, abstaining in zip(rows, guessed, accepted):
                row["discogs" + field.capitalize() + "Top"] = prediction
                row["discogs" + field.capitalize() + "Selective"] = abstaining
            if args.discogs_training:
                trained, predicted, selected = external_fit(args.discogs_training, field, vectors, expected, artists)
                report["expandedDiscogs" + field.capitalize()] = trained
                for row, prediction, abstaining in zip(rows, predicted, selected):
                    row["expandedDiscogs" + field.capitalize() + "Top"] = prediction
                    row["expandedDiscogs" + field.capitalize() + "Selective"] = abstaining
                if field == "embedding":
                    report["multilabelDiscogs"] = multilabel_fit(args.discogs_training, directory, rows, vectors, expected)
    (directory / "mobile-genre-benchmark.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "rows"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("model", type=Path)
    parser.add_argument("--discogs-features", type=Path)
    parser.add_argument("--discogs-training", type=Path)
    options = parser.parse_args()
    if options.discogs_training and not options.discogs_features:
        parser.error("--discogs-training requires --discogs-features")
    main(options)
