"""MFCC frontend shared in definition with firmware/src/kws.cpp."""

import numpy as np

SAMPLE_RATE = 16000
WINDOW_SAMPLES = 16000
FRAME_LENGTH = 480
FRAME_STEP = 320
FFT_SIZE = 512
MEL_BINS = 40
FEATURE_FRAMES = 49


def _hz_to_mel(hz):
    return 2595.0 * np.log10(1.0 + hz / 700.0)


def _mel_to_hz(mel):
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def mfcc(samples):
    """Return a (49, 40) log-mel MFCC feature array for a 1 s clip."""
    raw = np.asarray(samples)
    audio = raw.astype(np.float32).reshape(-1)
    if np.issubdtype(raw.dtype, np.integer):
        audio /= 32768.0
    if audio.size < WINDOW_SAMPLES:
        audio = np.pad(audio, (0, WINDOW_SAMPLES - audio.size))
    elif audio.size > WINDOW_SAMPLES:
        audio = audio[:WINDOW_SAMPLES]

    mel_points = _mel_to_hz(
        np.linspace(_hz_to_mel(0.0), _hz_to_mel(SAMPLE_RATE / 2), MEL_BINS + 2)
    )
    bin_hz = np.arange(FFT_SIZE // 2 + 1, dtype=np.float32) * SAMPLE_RATE / FFT_SIZE
    filters = np.zeros((MEL_BINS, FFT_SIZE // 2 + 1), dtype=np.float32)
    for mel in range(MEL_BINS):
        left, center, right = mel_points[mel : mel + 3]
        rising = (bin_hz >= left) & (bin_hz <= center)
        falling = (bin_hz > center) & (bin_hz <= right)
        if center > left:
            filters[mel, rising] = (bin_hz[rising] - left) / (center - left)
        if right > center:
            filters[mel, falling] = (right - bin_hz[falling]) / (right - center)

    frames = np.empty((FEATURE_FRAMES, MEL_BINS), dtype=np.float32)
    window = np.hamming(FRAME_LENGTH).astype(np.float32)
    cosine = np.cos(
        np.pi
        * (np.arange(MEL_BINS, dtype=np.float32)[:, None] + 0.5)
        * np.arange(MEL_BINS, dtype=np.float32)[None, :]
        / MEL_BINS
    )
    norms = np.sqrt(
        np.where(np.arange(MEL_BINS) == 0, 1.0 / MEL_BINS, 2.0 / MEL_BINS)
    )
    for frame_index in range(FEATURE_FRAMES):
        start = frame_index * FRAME_STEP
        frame = np.zeros(FFT_SIZE, dtype=np.float32)
        frame[:FRAME_LENGTH] = audio[start : start + FRAME_LENGTH] * window
        spectrum = np.fft.rfft(frame)
        power = spectrum.real**2 + spectrum.imag**2
        log_mel = np.log(np.maximum(filters @ power, 1.0e-6))
        frames[frame_index] = (cosine @ log_mel) * norms
    return frames
