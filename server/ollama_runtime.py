"""Start and check the local Ollama service used by Ira."""

import logging
import os
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

logger = logging.getLogger("ira.ollama")
STARTUP_TIMEOUT_SECONDS = 45
POLL_INTERVAL_SECONDS = 0.5
REQUEST_TIMEOUT_SECONDS = 2
_ollama_process = None


def _tags_url(ollama_url: str) -> str:
    parsed = urllib.parse.urlsplit(ollama_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise RuntimeError(f"Invalid Ollama URL: {ollama_url}")
    if parsed.username or parsed.password:
        raise RuntimeError("Credentials are not supported in the Ira Ollama URL.")

    if parsed.path.endswith("/api/generate"):
        path = parsed.path[: -len("generate")] + "tags"
    else:
        path = parsed.path.rstrip("/") + "/api/tags"
    return urllib.parse.urlunsplit(
        (parsed.scheme, parsed.netloc, path, "", "")
    )


def _is_ollama_available(tags_url: str) -> bool:
    try:
        with urllib.request.urlopen(
            tags_url,
            timeout=REQUEST_TIMEOUT_SECONDS,
        ) as response:
            return response.status == 200
    except urllib.error.URLError:
        return False


def _find_ollama_executable() -> str | None:
    executable = shutil.which("ollama")
    if executable:
        return executable

    if os.name == "nt":
        candidates = []
        local_app_data = os.environ.get("LOCALAPPDATA")
        if local_app_data:
            candidates.append(
                Path(local_app_data) / "Programs" / "Ollama" / "ollama.exe"
            )
        program_files = os.environ.get("ProgramFiles")
        if program_files:
            candidates.append(Path(program_files) / "Ollama" / "ollama.exe")
        for candidate in candidates:
            if candidate.is_file():
                return str(candidate)
    return None


def ensure_ollama_running(ollama_url: str) -> None:
    """Reuse a reachable Ollama service or start the local service and await readiness."""
    global _ollama_process

    tags_url = _tags_url(ollama_url)
    if _is_ollama_available(tags_url):
        logger.info("Ollama is already available at %s", ollama_url)
        return

    parsed = urllib.parse.urlsplit(ollama_url)
    is_local = parsed.scheme == "http" and parsed.hostname in {
        "localhost",
        "127.0.0.1",
        "::1",
    }
    if not is_local:
        raise RuntimeError(
            f"Could not reach Ollama at {ollama_url}. Ira can start Ollama only "
            "on this computer; check the configured remote Ollama service."
        )

    executable = _find_ollama_executable()
    if executable is None:
        raise RuntimeError(
            "Ollama is not responding and the 'ollama' command was not found. "
            "Install Ollama and add it to PATH before starting Ira."
        )

    environment = os.environ.copy()
    environment["OLLAMA_HOST"] = parsed.netloc
    process_options = {
        "env": environment,
        "stdin": subprocess.DEVNULL,
        "stdout": subprocess.DEVNULL,
        "stderr": subprocess.DEVNULL,
    }
    if os.name == "nt":
        process_options["creationflags"] = subprocess.DETACHED_PROCESS
    else:
        process_options["start_new_session"] = True

    try:
        _ollama_process = subprocess.Popen(
            [executable, "serve"],
            **process_options,
        )
    except OSError as error:
        raise RuntimeError(f"Could not start Ollama using {executable}: {error}") from error

    logger.info("Starting local Ollama service at %s", ollama_url)
    deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        if _is_ollama_available(tags_url):
            logger.info("Ollama is ready at %s", ollama_url)
            return
        exit_code = _ollama_process.poll()
        if exit_code is not None:
            raise RuntimeError(
                f"Ollama exited with code {exit_code} before becoming available "
                f"at {ollama_url}."
            )
        time.sleep(POLL_INTERVAL_SECONDS)

    raise RuntimeError(
        f"Ollama did not become available at {ollama_url} within "
        f"{STARTUP_TIMEOUT_SECONDS} seconds."
    )
