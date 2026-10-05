import unittest
from unittest.mock import patch

import ollama_runtime


class EnsureOllamaRunningTests(unittest.TestCase):
    def setUp(self):
        ollama_runtime._ollama_process = None

    @patch("ollama_runtime.subprocess.Popen")
    @patch("ollama_runtime._is_ollama_available", return_value=True)
    def test_reuses_running_service(self, is_available, popen):
        ollama_runtime.ensure_ollama_running("http://127.0.0.1:11434/api/generate")

        is_available.assert_called_once_with("http://127.0.0.1:11434/api/tags")
        popen.assert_not_called()

    @patch("ollama_runtime.time.sleep")
    @patch("ollama_runtime.subprocess.Popen")
    @patch("ollama_runtime.shutil.which", return_value="ollama")
    @patch(
        "ollama_runtime._is_ollama_available",
        side_effect=[False, False, True],
    )
    def test_starts_local_service_and_waits_for_readiness(
        self,
        is_available,
        which,
        popen,
        sleep,
    ):
        process = popen.return_value
        process.poll.return_value = None

        ollama_runtime.ensure_ollama_running("http://127.0.0.1:11434/api/generate")

        which.assert_called_once_with("ollama")
        popen.assert_called_once()
        self.assertEqual(popen.call_args.args[0], ["ollama", "serve"])
        self.assertEqual(
            popen.call_args.kwargs["env"]["OLLAMA_HOST"],
            "127.0.0.1:11434",
        )
        self.assertGreaterEqual(is_available.call_count, 2)
        sleep.assert_called_once_with(ollama_runtime.POLL_INTERVAL_SECONDS)

    def test_does_not_start_local_process_for_unavailable_remote_service(
        self,
    ):
        with (
            patch("ollama_runtime._is_ollama_available", return_value=False),
            patch("ollama_runtime.subprocess.Popen") as popen,
        ):
            with self.assertRaisesRegex(RuntimeError, "only on this computer"):
                ollama_runtime.ensure_ollama_running(
                    "http://192.0.2.10:11434/api/generate"
                )

        popen.assert_not_called()

    def test_reports_when_started_process_exits(self):
        with (
            patch(
                "ollama_runtime._is_ollama_available",
                side_effect=[False, False],
            ),
            patch("ollama_runtime.shutil.which", return_value="ollama"),
            patch("ollama_runtime.subprocess.Popen") as popen,
        ):
            popen.return_value.poll.return_value = 2

            with self.assertRaisesRegex(RuntimeError, "exited with code 2"):
                ollama_runtime.ensure_ollama_running(
                    "http://127.0.0.1:11434/api/generate"
                )


if __name__ == "__main__":
    unittest.main()
