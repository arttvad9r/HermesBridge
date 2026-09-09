from __future__ import annotations

import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"


class AppPermissionsMcpTest(unittest.TestCase):
    def test_app_permissions_maps_to_launcher_scoped_android_tool(self) -> None:
        captured: dict[str, object] = {}

        def fake_device_command(
            device_id: str,
            tool: str,
            arguments: dict[str, object] | None = None,
            *,
            timeout: int = 15,
        ) -> dict[str, object]:
            captured.update(
                device_id=device_id,
                tool=tool,
                arguments=arguments,
                timeout=timeout,
            )
            return {"ok": True}

        with patch.object(server, "_device_command", side_effect=fake_device_command):
            result = server.app_permissions(DEVICE_ID, " com.example.visible ")

        self.assertEqual(result, {"ok": True})
        self.assertEqual(
            captured,
            {
                "device_id": DEVICE_ID,
                "tool": "apps.permissions",
                "arguments": {"packageName": "com.example.visible"},
                "timeout": 15,
            },
        )

    def test_app_permissions_allows_reading_bridge_package(self) -> None:
        with patch.object(
            server,
            "_device_command",
            return_value={"ok": True},
        ) as command:
            server.app_permissions(DEVICE_ID, server.HERMES_BRIDGE_PACKAGE)

        command.assert_called_once_with(
            DEVICE_ID,
            "apps.permissions",
            {"packageName": server.HERMES_BRIDGE_PACKAGE},
        )

    def test_mutating_package_tools_still_protect_bridge_package(self) -> None:
        with self.assertRaisesRegex(ValueError, "protected"):
            server.force_stop_app(DEVICE_ID, server.HERMES_BRIDGE_PACKAGE)

    def test_app_permissions_rejects_invalid_package_name(self) -> None:
        with patch.object(server, "_device_command") as command:
            with self.assertRaisesRegex(ValueError, "valid Android package name"):
                server.app_permissions(DEVICE_ID, "../bad")
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
