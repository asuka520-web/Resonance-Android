"""Convert downloaded JamendoLyrics MP3s for the local blind-listening pilot."""

import array
import csv
import json
import math
import re
import sys
import wave
from pathlib import Path

import miniaudio


def write_wav(path, samples, rate):
    with wave.open(str(path), "wb") as audio:
        audio.setparams((1, 2, rate, 0, "NONE", "not compressed"))
        audio.writeframes(samples.tobytes())


def decode(path, rate):
    return miniaudio.decode_file(str(path), output_format=miniaudio.SampleFormat.SIGNED16,
                                  nchannels=1, sample_rate=rate).samples


def main(directory):
    clips = directory / "clips"
    clips.mkdir(exist_ok=True)
    manifest = []
    with (directory / "selected.csv").open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            track_id = re.search(r"/track/(\d+)", row["URL"]).group(1)
            mp3 = directory / f"{track_id}.mp3"
            if not mp3.is_file():
                continue
            try:
                signal = decode(mp3, 16000)
                if len(signal) < 55 * 16000:
                    print(f"Skipping {track_id}: fewer than 55 seconds available")
                    continue
                rate = 44100
                preview = decode(mp3, rate)[15 * rate:27 * rate]
            except miniaudio.DecodeError:
                print(f"Skipping {track_id}: audio could not be decoded")
                continue
            names = []
            for offset in (15, 40):
                destination = clips / f"{track_id}-{offset}.wav"
                write_wav(destination, signal[offset * 16000:(offset + 15) * 16000], 16000)
                names.append(destination.relative_to(directory).as_posix())

            rms = math.sqrt(sum(value * value for value in preview) / len(preview))
            peak = max(abs(value) for value in preview)
            gain = min(2, 3200 / rms, 32760 / peak) if rms > 0 else 1
            listen = clips / f"{track_id}-listen.wav"
            write_wav(listen, array.array("h", (round(value * gain) for value in preview)), rate)
            manifest.append(dict(id=track_id, artist=row["Artist"], title=row["Title"], tag=row["Genre"],
                                 language=row["Language"], license=row["LicenseType"], url=row["URL"], clips=names,
                                 listen=listen.relative_to(directory).as_posix()))

    (directory / "similarity-input.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Prepared {len(manifest)} tracks with two model windows each")


if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
