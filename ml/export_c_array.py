"""Export a .tflite flatbuffer to a C++ header without xxd."""

import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--model", type=Path, default=Path(__file__).parent / "model.tflite"
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).parent.parent
        / "firmware"
        / "src"
        / "model_data.h",
    )
    args = parser.parse_args()
    data = args.model.read_bytes()
    args.output.parent.mkdir(parents=True, exist_ok=True)

    lines = [
        "#pragma once",
        "",
        "#include <stdint.h>",
        "",
        "alignas(16) const unsigned char g_model_data[] = {",
    ]
    for start in range(0, len(data), 12):
        chunk = data[start : start + 12]
        lines.append("    " + ", ".join(f"0x{byte:02x}" for byte in chunk) + ",")
    lines.extend(
        [
            "};",
            "constexpr unsigned int g_model_data_len = sizeof(g_model_data);",
            "constexpr bool g_model_data_is_placeholder = false;",
            "",
        ]
    )
    args.output.write_text("\n".join(lines), encoding="ascii")
    print(f"Exported {len(data)} bytes to {args.output}")


if __name__ == "__main__":
    main()
