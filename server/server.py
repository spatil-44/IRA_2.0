"""Offline-capable WebSocket audio receiver and faster-whisper transcription service."""

import asyncio
import json
import logging
import os
import threading
import time
import urllib.error
import urllib.request

import av
import numpy as np
import websockets
from faster_whisper import WhisperModel
from ollama_runtime import ensure_ollama_running

HOST = "0.0.0.0"
PORT = 8765
SAMPLE_RATE = 16000
MAX_AUDIO_SECONDS = 30
MODEL_NAME = os.environ.get("IRA_WHISPER_MODEL", "small")
DEVICE = os.environ.get("IRA_WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.environ.get("IRA_WHISPER_COMPUTE_TYPE", "int8")
OLLAMA_URL = os.environ.get("IRA_OLLAMA_URL", "http://127.0.0.1:11434/api/generate")
OLLAMA_MODEL = os.environ.get("IRA_OLLAMA_MODEL", "tinyllama:latest")
OLLAMA_TIMEOUT_SECONDS = 240

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
logger = logging.getLogger("ira.server")
model = None
model_lock = threading.Lock()


def new_decoder():
    decoder = av.CodecContext.create("opus", "r")
    decoder.sample_rate = 48000
    decoder.layout = "mono"
    decoder.open()
    return decoder, av.AudioResampler(format="s16", layout="mono", rate=SAMPLE_RATE)


def decode_packet(decoder, resampler, payload):
    output = []
    for decoded_frame in decoder.decode(av.Packet(payload)):
        for frame in resampler.resample(decoded_frame):
            output.append(frame.to_ndarray().reshape(-1).astype(np.float32) / 32768.0)
    return output


def flush_decoder(decoder, resampler):
    output = []
    for decoded_frame in decoder.decode(None):
        for frame in resampler.resample(decoded_frame):
            output.append(frame.to_ndarray().reshape(-1).astype(np.float32) / 32768.0)
    for frame in resampler.resample(None):
        output.append(frame.to_ndarray().reshape(-1).astype(np.float32) / 32768.0)
    return output


def transcribe_audio(audio):
    with model_lock:
        segments, _ = model.transcribe(audio, vad_filter=True)
        return " ".join(segment.text.strip() for segment in segments).strip()


def generate_assistant_reply(transcript):
    request_body = json.dumps(
        {
            "model": OLLAMA_MODEL,
            "system": (
                "You are Ira, a helpful, friendly voice assistant. "
                "Answer the user's request clearly and concisely. "
                "If the request is ambiguous, ask one brief clarification question."
            ),
            "prompt": transcript,
            "stream": False,
            "options": {"temperature": 0.4, "num_predict": 160},
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        OLLAMA_URL,
        data=request_body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=OLLAMA_TIMEOUT_SECONDS) as response:
            result = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"Ollama returned HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"Could not reach Ollama at {OLLAMA_URL}: {error.reason}") from error
    except TimeoutError as error:
        raise RuntimeError("Ollama did not finish generating a reply in time.") from error

    reply = result.get("response", "").strip()
    if not reply:
        raise RuntimeError("Ollama returned an empty assistant reply.")
    return reply


async def send_error(websocket, message):
    await websocket.send(json.dumps({"type": "error", "message": message}))


async def handler(websocket, path=None):
    del path
    await websocket.send(
        json.dumps({"type": "ready", "sample_rate": SAMPLE_RATE})
    )
    decoder = None
    resampler = None
    stream_codec = None
    audio_parts = []
    audio_samples = 0
    stream_started = None
    packet_count = 0

    async for message in websocket:
        if isinstance(message, bytes):
            if stream_codec is None:
                await send_error(websocket, "Send a start control message before audio.")
                continue
            if stream_codec == "pcm_s16le":
                if len(message) % 2:
                    await send_error(websocket, "PCM audio must contain whole 16-bit samples.")
                    continue
                decoded = [
                    np.frombuffer(message, dtype="<i2").astype(np.float32) / 32768.0
                ]
            else:
                try:
                    decoded = decode_packet(decoder, resampler, message)
                except av.error.FFmpegError as error:
                    logger.warning("Rejected invalid Opus packet: %s", error)
                    await send_error(websocket, "Invalid Opus packet.")
                    continue
            for part in decoded:
                audio_samples += part.size
                if audio_samples > MAX_AUDIO_SECONDS * SAMPLE_RATE:
                    await send_error(websocket, "Audio stream exceeds the server limit.")
                    return
                audio_parts.append(part)
            packet_count += 1
            continue

        try:
            control = json.loads(message)
        except json.JSONDecodeError:
            await send_error(websocket, "Invalid JSON control message.")
            continue
        if not isinstance(control, dict):
            await send_error(websocket, "Control message must be a JSON object.")
            continue

        message_type = control.get("type")
        if message_type == "start":
            if control.get("sample_rate") != SAMPLE_RATE:
                await send_error(websocket, "Only 16 kHz audio streams are supported.")
                continue
            codec = control.get("codec", "opus")
            if codec not in {"opus", "pcm_s16le"}:
                await send_error(websocket, "Unsupported audio codec.")
                continue
            if codec == "opus":
                try:
                    decoder, resampler = new_decoder()
                except av.error.FFmpegError as error:
                    logger.exception("Could not initialize the Opus decoder.")
                    await send_error(
                        websocket, f"Opus decoder initialization failed: {error}"
                    )
                    continue
            else:
                decoder, resampler = None, None
            stream_codec = codec
            audio_parts = []
            audio_samples = 0
            packet_count = 0
            stream_started = time.perf_counter()
        elif message_type == "end":
            if stream_codec is None or stream_started is None:
                await send_error(websocket, "No active audio stream.")
                continue
            try:
                if stream_codec == "opus":
                    audio_parts.extend(flush_decoder(decoder, resampler))
                audio = (
                    np.concatenate(audio_parts)
                    if audio_parts
                    else np.empty(0, dtype=np.float32)
                )
                if audio.size == 0:
                    await send_error(websocket, "The stream contained no decodable audio.")
                    decoder = None
                    continue
                transcript = await asyncio.to_thread(transcribe_audio, audio)
                assistant_started = time.perf_counter()
                try:
                    assistant_reply = await asyncio.to_thread(
                        generate_assistant_reply, transcript
                    )
                    assistant_error = None
                except (RuntimeError, OSError, ValueError) as error:
                    logger.exception("Assistant reply generation failed.")
                    assistant_reply = None
                    assistant_error = str(error)
                assistant_latency_ms = (
                    time.perf_counter() - assistant_started
                ) * 1000
                latency_ms = (time.perf_counter() - stream_started) * 1000
                logger.info(
                    "Transcribed %d audio frames (%d ms audio) in %.1f ms; "
                    "assistant generation took %.1f ms",
                    packet_count,
                    round(audio.size * 1000 / SAMPLE_RATE),
                    latency_ms,
                    assistant_latency_ms,
                )
                result = {
                    "type": "transcript",
                    "text": transcript,
                    "latency_ms": round(latency_ms, 1),
                    "assistant_latency_ms": round(assistant_latency_ms, 1),
                }
                if assistant_reply is not None:
                    result["assistant_reply"] = assistant_reply
                if assistant_error is not None:
                    result["assistant_error"] = assistant_error
                await websocket.send(
                    json.dumps(result)
                )
            except (av.error.FFmpegError, RuntimeError, ValueError, OSError) as error:
                logger.exception("Transcription failed.")
                await send_error(websocket, f"Transcription failed: {error}")
            finally:
                decoder = None
                resampler = None
                stream_codec = None
                audio_parts = []
                audio_samples = 0
                stream_started = None
        else:
            await send_error(websocket, "Unknown control message type.")


async def main():
    global model
    try:
        ensure_ollama_running(OLLAMA_URL)
    except RuntimeError as error:
        logger.error("Could not start Ollama: %s", error)
        raise SystemExit(1) from error

    logger.info(
        "Loading Whisper model %s (device=%s, compute_type=%s)",
        MODEL_NAME,
        DEVICE,
        COMPUTE_TYPE,
    )
    model = WhisperModel(MODEL_NAME, device=DEVICE, compute_type=COMPUTE_TYPE)
    logger.info("Listening on ws://%s:%d", HOST, PORT)
    async with websockets.serve(handler, HOST, PORT, max_size=1024 * 1024):
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Server stopped.")
