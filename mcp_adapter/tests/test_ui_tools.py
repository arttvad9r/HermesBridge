import unittest
from unittest.mock import patch

from typeddroid_mcp import server

DEVICE_ID = "device_12345678-1234-1234-1234-1234567890ab"


class UiToolsMcpTest(unittest.TestCase):
    def test_read_and_action_tools_map_to_fixed_android_tools(self) -> None:
        with patch.object(server, "_device_command", return_value={"ok": True}) as command:
            server.ui_hierarchy(DEVICE_ID)
            command.assert_called_with(DEVICE_ID, "ui.hierarchy", timeout=30)
            server.ui_screenshot(DEVICE_ID)
            command.assert_called_with(DEVICE_ID, "ui.screenshot", timeout=30)
            server.ui_tap(DEVICE_ID, 10, 20)
            command.assert_called_with(DEVICE_ID, "ui.tap", {"x": 10, "y": 20})
            server.ui_swipe(DEVICE_ID, 1, 2, 3, 4, 500)
            command.assert_called_with(
                DEVICE_ID,
                "ui.swipe",
                {"startX": 1, "startY": 2, "endX": 3, "endY": 4, "durationMillis": 500},
            )
            server.ui_text(DEVICE_ID, "hello world")
            command.assert_called_with(DEVICE_ID, "ui.text", {"text": "hello world"})
            server.ui_key(DEVICE_ID, 4)
            command.assert_called_with(DEVICE_ID, "ui.key", {"keyCode": 4})
            server.open_app(DEVICE_ID, "com.example.app")
            command.assert_called_with(DEVICE_ID, "apps.open", {"packageName": "com.example.app"})

    def test_ui_arguments_are_rejected_before_request(self) -> None:
        with patch.object(server, "_device_command") as command:
            for value in (-1, server.MAX_UI_COORDINATE + 1, True, 1.5):
                with self.assertRaises(ValueError):
                    server.ui_tap(DEVICE_ID, value, 1)  # type: ignore[arg-type]
            with self.assertRaises(ValueError):
                server.ui_swipe(DEVICE_ID, 0, 0, 1, 1, 0)
            with self.assertRaises(ValueError):
                server.ui_text(DEVICE_ID, "x\x00y")
            with self.assertRaises(ValueError):
                server.ui_text(DEVICE_ID, "x" * (server.MAX_UI_TEXT_BYTES + 1))
            with self.assertRaises(ValueError):
                server.ui_key(DEVICE_ID, server.MAX_UI_KEY_CODE + 1)
            with self.assertRaises(ValueError):
                server.open_app(DEVICE_ID, "not a package")
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
