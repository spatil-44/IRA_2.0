"""Train a small DS-CNN wake-word model from ml/data/<class>/*.wav."""

import argparse
from pathlib import Path

import numpy as np
import tensorflow as tf
from scipy.io import wavfile
from scipy.signal import resample_poly

from features import SAMPLE_RATE, mfcc


def load_dataset(root):
    features, labels = [], []
    paths = sorted(root.rglob("*.wav"))
    if not paths:
        raise SystemExit(f"No WAV files found in {root}")
    found_classes = set()
    for path in paths:
        class_name = path.relative_to(root).parts[0]
        label = 0 if class_name.lower() == "ira" else 1
        found_classes.add(label)
        rate, audio = wavfile.read(path)
        if np.issubdtype(audio.dtype, np.integer):
            audio = audio.astype(np.float32) / (np.iinfo(audio.dtype).max + 1)
        else:
            audio = audio.astype(np.float32)
        if audio.ndim == 2:
            audio = audio.mean(axis=1)
        if rate != SAMPLE_RATE:
            divisor = np.gcd(rate, SAMPLE_RATE)
            audio = resample_poly(audio, SAMPLE_RATE // divisor, rate // divisor)
        features.append(mfcc(audio))
        labels.append(label)
    if found_classes != {0, 1}:
        raise SystemExit(f"Need WAV examples in {root / 'ira'} and at least one negative class folder.")
    return np.asarray(features, dtype=np.float32)[..., np.newaxis], np.asarray(labels)


def build_model():
    inputs = tf.keras.Input(shape=(49, 40, 1))
    x = tf.keras.layers.Conv2D(24, 3, strides=2, padding="same", use_bias=False)(inputs)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU()(x)
    for channels, stride in ((32, 1), (48, 2), (64, 1)):
        x = tf.keras.layers.DepthwiseConv2D(
            3, strides=stride, padding="same", use_bias=False
        )(x)
        x = tf.keras.layers.BatchNormalization()(x)
        x = tf.keras.layers.ReLU()(x)
        x = tf.keras.layers.Conv2D(channels, 1, padding="same", use_bias=False)(x)
        x = tf.keras.layers.BatchNormalization()(x)
        x = tf.keras.layers.ReLU()(x)
    x = tf.keras.layers.GlobalAveragePooling2D()(x)
    outputs = tf.keras.layers.Dense(2, activation="softmax")(x)
    model = tf.keras.Model(inputs, outputs)
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, default=Path(__file__).parent / "data")
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).parent / "artifacts" / "ira_kws.h5",
    )
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    np.random.seed(args.seed)
    tf.random.set_seed(args.seed)
    x, y = load_dataset(args.data)
    train_ids, validation_ids = [], []
    for label in np.unique(y):
        ids = np.flatnonzero(y == label)
        np.random.shuffle(ids)
        split = max(1, int(len(ids) * 0.8))
        train_ids.extend(ids[:split])
        validation_ids.extend(ids[split:])
    if not validation_ids:
        raise SystemExit("At least two examples per class are required for a validation split.")

    x_train, y_train = x[train_ids], y[train_ids]
    x_validation, y_validation = x[validation_ids], y[validation_ids]
    model = build_model()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    model.fit(
        x_train,
        y_train,
        validation_data=(x_validation, y_validation),
        epochs=args.epochs,
        batch_size=min(32, max(1, len(y_train))),
        callbacks=[
            tf.keras.callbacks.EarlyStopping(
                monitor="val_accuracy", patience=8, restore_best_weights=True
            )
        ],
        verbose=2,
    )
    model.save(args.output)
    print(f"Saved trained float model to {args.output}")


if __name__ == "__main__":
    main()
