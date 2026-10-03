"""Record labelled one-second, 16 kHz mono WAV clips from the default microphone."""

import argparse
from pathlib import Path

import numpy as np
import sounddevice as sd
from scipy.io import wavfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--label",
        required=True,
        help="Class name, e.g. ira, unknown, noise, or a negative phrase.",
    )
    parser.add_argument("--count", type=int, default=20)
    parser.add_argument("--seconds", type=float, default=1.0)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "data")
    args = parser.parse_args()

    if args.count < 1 or args.seconds <= 0:
        parser.error("--count and --seconds must be positive")
    destination = args.output / args.label
    destination.mkdir(parents=True, exist_ok=True)
    sample_count = round(args.seconds * 16000)

    for index in range(args.count):
        input(f"Press Enter to record {args.label} clip {index + 1}/{args.count}...")
        recording = sd.rec(
            sample_count,
            samplerate=16000,
            channels=1,
            dtype="int16",
            blocking=True,
        )
        audio = np.asarray(recording[:, 0], dtype=np.int16)
        output = destination / f"{args.label}_{index + 1:04d}.wav"
        wavfile.write(output, 16000, audio)
        print(f"Saved {output}")


if __name__ == "__main__":
    main()
