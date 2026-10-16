import unittest
from unittest.mock import patch

from typeddroid_mcp import server

DEVICE_ID = "device_12345678-1234-1234-1234-1234567890ab"


class NotificationToolsMcpTest(unittest.TestCase):
    def test_notification_tools_map_to_fixed_android_tools(self) -> None:
        with patch.object(server, "_device_command", return_value={"ok": True}) as command:
            server.list_notifications(DEVICE_ID, 12)
            command.assert_called_with(DEVICE_ID, "notifications.list", {"limit": 12})

            server.dismiss_notification(DEVICE_ID, "key", idempotency_key="dismiss-1")
            command.assert_called_with(
                DEVICE_ID,
                "notifications.dismiss",
                {"notificationKey": "key"},
                idempotency_key="dismiss-1",
            )

            server.open_notification(DEVICE_ID, "key")
            command.assert_called_with(DEVICE_ID, "notifications.open", {"notificationKey": "key"})

            server.notification_action(DEVICE_ID, "key", 2)
            command.assert_called_with(
                DEVICE_ID,
                "notifications.action",
                {"notificationKey": "key", "actionIndex": 2},
            )

            server.reply_notification(DEVICE_ID, "key", 1, "hello")
            command.assert_called_with(
                DEVICE_ID,
                "notifications.reply",
                {"notificationKey": "key", "actionIndex": 1, "text": "hello"},
            )

    def test_notification_arguments_fail_before_transport(self) -> None:
        with patch.object(server, "_device_command") as command:
            with self.assertRaises(ValueError):
                server.list_notifications(DEVICE_ID, 0)
            with self.assertRaises(ValueError):
                server.dismiss_notification(DEVICE_ID, "")
            with self.assertRaises(ValueError):
                server.notification_action(DEVICE_ID, "key", 16)
            with self.assertRaises(ValueError):
                server.reply_notification(DEVICE_ID, "key", 0, "x\x00y")
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
