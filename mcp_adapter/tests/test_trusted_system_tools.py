import unittest
from unittest.mock import patch

from typeddroid_mcp import server

DEVICE_ID = "device_12345678-1234-1234-1234-1234567890ab"


class TrustedSystemToolsMcpTest(unittest.TestCase):
    def test_fixed_tools_map_to_android_tool_names(self) -> None:
        with patch.object(server, "_device_command", return_value={"ok": True}) as command:
            server.device_capabilities(DEVICE_ID)
            command.assert_called_with(DEVICE_ID, "device.capabilities")

            server.settings_get(DEVICE_ID, "GLOBAL", "airplane_mode_on")
            command.assert_called_with(
                DEVICE_ID,
                "settings.get",
                {"namespace": "global", "key": "airplane_mode_on"},
            )

            server.settings_put(DEVICE_ID, "secure", "typed_droid_test", "hello world")
            command.assert_called_with(
                DEVICE_ID,
                "settings.put",
                {"namespace": "secure", "key": "typed_droid_test", "value": "hello world"},
            )

            server.appops_get(DEVICE_ID, "com.example.app", "CAMERA")
            command.assert_called_with(
                DEVICE_ID,
                "appops.get",
                {"packageName": "com.example.app", "opName": "CAMERA"},
            )

            server.appops_set(DEVICE_ID, "com.example.app", "CAMERA", "IGNORE")
            command.assert_called_with(
                DEVICE_ID,
                "appops.set",
                {"packageName": "com.example.app", "opName": "CAMERA", "mode": "ignore"},
            )

            server.process_list(DEVICE_ID)
            command.assert_called_with(DEVICE_ID, "process.list", timeout=30)

            server.logcat_read(DEVICE_ID, 50, "system")
            command.assert_called_with(
                DEVICE_ID,
                "logcat.read",
                {"lines": 50, "buffer": "system"},
                timeout=45,
            )

    def test_activity_start_uses_bounded_structured_arguments(self) -> None:
        with patch.object(server, "_device_command", return_value={"ok": True}) as command:
            server.activity_start(
                DEVICE_ID,
                component="com.example.app/.MainActivity",
                action="android.intent.action.VIEW",
                data_uri="https://example.com/a?b=c",
            )
            command.assert_called_with(
                DEVICE_ID,
                "activity.start",
                {
                    "component": "com.example.app/.MainActivity",
                    "action": "android.intent.action.VIEW",
                    "dataUri": "https://example.com/a?b=c",
                },
                timeout=45,
            )

    def test_invalid_system_arguments_are_rejected_before_request(self) -> None:
        with patch.object(server, "_device_command") as command:
            with self.assertRaises(ValueError):
                server.activity_start(DEVICE_ID)
            with self.assertRaises(ValueError):
                server.activity_start(DEVICE_ID, component="bad component")
            with self.assertRaises(ValueError):
                server.settings_get(DEVICE_ID, "private", "x")
            with self.assertRaises(ValueError):
                server.settings_put(DEVICE_ID, "global", "bad key", "x")
            with self.assertRaises(ValueError):
                server.appops_set(DEVICE_ID, "io.github.arttvad9r.typeddroid", "CAMERA", "ignore")
            with self.assertRaises(ValueError):
                server.appops_set(DEVICE_ID, "com.example.app", "CAMERA", "maybe")
            with self.assertRaises(ValueError):
                server.logcat_read(DEVICE_ID, server.MAX_LOGCAT_LINES + 1)
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
