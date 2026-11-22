"""Select and download English recordings for a local, independent listening test."""

import argparse
import csv
import io
import random
import re
import subprocess
import zipfile
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


GENRES = {
    "R&B / Soul": {"rnb", "soul", "neosoul"},
    "Hip-Hop": {"hiphop", "rap"},
    "Electronic": {"electronic", "electronica"},
    "Reggae": {"reggae"},
    "Folk": {"folk", "popfolk"},
    "Rock": {"rock", "poprock"},
    "Indie": {"indie"},
    "Pop": {"pop"},
}
MIRROR = "https://cdn.freesound.org/mtg-jamendo/raw_30s/audio-low/"


def english_recordings(directory):
    overlaps = defaultdict(lambda: [0, 0])
    other_language = set()
    with zipfile.ZipFile(directory / "lyricwhiz.zip") as archive:
        for split in ("train", "valid", "test"):
            name = f"LyricWhiz-main/MulJam_v2.0/preconstructed-split/{split}.meta"
            with io.TextIOWrapper(archive.open(name), encoding="utf-8") as source:
                for row in csv.DictReader(source):
                    file = row["file"]
                    if row["lang"] != "en":
                        other_language.add(file)
                        continue
                    start, end = float(row["start"]), float(row["end"])
                    for index, offset in enumerate((15, 40)):
                        overlaps[file][index] += max(0, min(end, offset + 15) - max(start, offset))
    return {file for file, overlap in overlaps.items() if min(overlap) >= 3 and file not in other_language}


def tsv(path):
    with path.open(encoding="utf-8", newline="") as source:
        rows = csv.reader(source, delimiter="\t")
        next(rows)
        yield from rows


def normalized(value):
    return "".join(character for character in value.casefold() if character.isalnum())


def candidates(directory, original, holdout=None):
    english = english_recordings(directory)
    licenses = dict(re.findall(r"(?m)^([0-9]+/[0-9]+\.mp3)\r?\n[^\r\n]+\r?\nAvailable under a Creative Commons (.+)",
                               (directory / "mtg-licenses.txt").read_text(encoding="utf-8")))
    with (original / "selected.csv").open(encoding="utf-8-sig", newline="") as source:
        prior = list(csv.DictReader(source))
    if holdout is not None:
        with (holdout / "selected.csv").open(encoding="utf-8-sig", newline="") as source:
            prior.extend(csv.DictReader(source))
    prior_artists = {normalized(row["Artist"]) for row in prior}
    prior_urls = {re.search(r"/track/(\d+)", row["URL"])[1] for row in prior}
    names = {row[0]: row for row in tsv(directory / "mtg-meta.tsv")}
    groups = defaultdict(list)
    for track, artist_id, _, file, duration, *raw_tags in tsv(directory / "mtg-tags.tsv"):
        mp3 = file.replace(".mp3", ".low.mp3")
        if mp3 not in english or file not in licenses or float(duration) < 55 or track not in names:
            continue
        _, _, _, title, artist, _, _, url = names[track]
        if not title or not artist or normalized(artist) in prior_artists or Path(file).stem in prior_urls:
            continue
        tags = {tag.removeprefix("genre---") for tag in raw_tags if tag.startswith("genre---")}
        for genre, family in GENRES.items():
            if family & tags:
                groups[genre].append(dict(URL=url.replace("http:", "https:", 1), Filepath=mp3, Artist=artist,
                                          Title=title, Genre=genre, LicenseType=licenses[file],
                                          Language="English", ArtistId=artist_id, Tags="|".join(sorted(tags))))
    rng = random.Random(520)
    for group in groups.values():
        rng.shuffle(group)
    return groups


def download(directory, row, prefix_bytes):
    target = directory / (Path(row["Filepath"]).name.removesuffix(".low.mp3") + ".mp3")
    if target.is_file() and target.stat().st_size > 100_000:
        return True
    partial = target.with_suffix(".part")
    limits = ["--range", f"0-{prefix_bytes - 1}"] if prefix_bytes else []
    result = subprocess.run(["curl.exe", "-fsSL", "--connect-timeout", "8", "--max-time", "50",
                             *limits, MIRROR + row["Filepath"], "-o", str(partial)], capture_output=True)
    if result.returncode == 0 and partial.stat().st_size > 100_000:
        partial.replace(target)
        return True
    partial.unlink(missing_ok=True)
    print(f"Unavailable: {row['Filepath']}", flush=True)
    return False


def main(directory, original, metadata, holdout, per_genre, prefix_bytes):
    directory.mkdir(parents=True, exist_ok=True)
    groups = candidates(metadata or directory, original, holdout)
    selected = []
    used_artists = set()
    used_names = set()
    used_tracks = set()
    with ThreadPoolExecutor(max_workers=12) as pool:
        for genre in GENRES:
            ready = 0
            group = []
            for row in groups[genre]:
                artist = normalized(row["Artist"])
                if row["ArtistId"] in used_artists or artist in used_names or row["Filepath"] in used_tracks:
                    continue
                used_artists.add(row["ArtistId"])
                used_names.add(artist)
                used_tracks.add(row["Filepath"])
                group.append(row)
                if len(group) == per_genre + max(4, per_genre // 3):
                    break
            for row, success in zip(group, pool.map(lambda row: download(directory, row, prefix_bytes), group)):
                if success and ready < per_genre:
                    selected.append(row)
                    ready += 1
            print(f"{genre}: {ready} tracks ready", flush=True)
    if not selected:
        raise SystemExit("No audio was available")
    with (directory / "selected.csv").open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=selected[0])
        writer.writeheader()
        writer.writerows(selected)
    print(f"Selected {len(selected)} English tracks from distinct artists")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("original", type=Path)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--holdout", type=Path)
    parser.add_argument("--per-genre", type=int, default=10)
    parser.add_argument("--prefix-bytes", type=int)
    args = parser.parse_args()
    if args.per_genre < 1:
        parser.error("--per-genre must be positive")
    if args.prefix_bytes is not None and args.prefix_bytes < 1:
        parser.error("--prefix-bytes must be positive")
    main(args.directory, args.original, args.metadata, args.holdout, args.per_genre, args.prefix_bytes)
