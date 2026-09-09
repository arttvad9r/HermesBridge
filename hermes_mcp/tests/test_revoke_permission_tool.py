from __future__ import annotations

import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"


class RevokePermissionMcpTest(unittest.TestCase):
    def test_revoke_permission_maps_only_to_typed_android_tool(self) -> None:
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
            result = server.revoke_app_permission(
                DEVICE_ID,
                " com.example.visible ",
                " android.permission.CAMERA ",
            )

        self.assertEqual(result, {"ok": True})
        self.assertEqual(
            captured,
            {
                "device_id": DEVICE_ID,
                "tool": "apps.revokePermission",
                "arguments": {
                    "packageName": "com.example.visible",
                    "permissionName": "android.permission.CAMERA",
                },
                "timeout": 75,
            },
        )

    def test_revoke_permission_rejects_bridge_package_before_request(self) -> None:
        with patch.object(server, "_device_command") as command:
            with self.assertRaisesRegex(ValueError, "protected"):
                server.revoke_app_permission(
                    DEVICE_ID,
                    server.HERMES_BRIDGE_PACKAGE,
                    "android.permission.CAMERA",
                )
            command.assert_not_called()

    def test_revoke_permission_rejects_invalid_permission_name_before_request(self) -> None:
        with patch.object(server, "_device_command") as command:
            with self.assertRaisesRegex(ValueError, "permission_name"):
                server.revoke_app_permission(
                    DEVICE_ID,
                    "com.example.visible",
                    "../CAMERA",
                )
            command.assert_not_called()

    def test_revoke_permission_does_not_accept_user_id(self) -> None:
        with self.assertRaises(TypeError):
            server.revoke_app_permission(
                DEVICE_ID,
                "com.example.visible",
                "android.permission.CAMERA",
                10,
            )


if __name__ == "__main__":
    unittest.main()
