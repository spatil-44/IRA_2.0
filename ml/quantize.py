"""Convert the trained model to fully integer INT8 TFLite."""

import argparse
from pathlib import Path

import numpy as np
import tensorflow as tf

from train import load_dataset


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--model",
        type=Path,
        default=Path(__file__).parent / "artifacts" / "ira_kws.h5",
    )
    parser.add_argument("--data", type=Path, default=Path(__file__).parent / "data")
    parser.add_argument(
        "--output", type=Path, default=Path(__file__).parent / "model.tflite"
    )
    args = parser.parse_args()

    model = tf.keras.models.load_model(args.model)
    representative, _ = load_dataset(args.data)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = lambda: (
        [sample[np.newaxis, ...].astype(np.float32)]
        for sample in representative[: min(500, len(representative))]
    )
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.int8

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(converter.convert())
    print(f"Saved fully quantized model to {args.output}")


if __name__ == "__main__":
    main()
