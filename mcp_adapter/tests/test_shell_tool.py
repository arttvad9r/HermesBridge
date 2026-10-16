import unittest
from unittest.mock import patch

from typeddroid_mcp import server


DEVICE_ID = "device_12345678-1234-1234-1234-1234567890ab"


class RawShellMcpTest(unittest.TestCase):
    def test_run_shell_maps_only_to_shell_run(self) -> None:
        with patch.object(server, "_device_command", return_value={"ok": True}) as command:
            result = server.run_shell(DEVICE_ID, "id && getprop ro.build.version.release", 45)

        self.assertEqual({"ok": True}, result)
        command.assert_called_once_with(
            DEVICE_ID,
            "shell.run",
            {
                "command": "id && getprop ro.build.version.release",
                "timeoutMillis": 45_000,
            },
            timeout=60,
        )

    def test_run_shell_rejects_nul_and_oversized_command_before_request(self) -> None:
        with patch.object(server, "_device_command") as command:
            with self.assertRaises(ValueError):
                server.run_shell(DEVICE_ID, "id\x00whoami")
            with self.assertRaises(ValueError):
                server.run_shell(DEVICE_ID, "x" * (server.MAX_SHELL_COMMAND_BYTES + 1))
        command.assert_not_called()

    def test_run_shell_rejects_invalid_timeout_before_request(self) -> None:
        with patch.object(server, "_device_command") as command:
            for value in (0, 301, True, 1.5):
                with self.assertRaises(ValueError):
                    server.run_shell(DEVICE_ID, "id", value)  # type: ignore[arg-type]
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
