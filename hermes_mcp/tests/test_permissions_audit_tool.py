from __future__ import annotations

import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"


class PermissionsAuditMcpTest(unittest.TestCase):
    def test_permissions_audit_maps_only_to_typed_tool(self) -> None:
        with patch.object(server, "_device_command", return_value={"ok": True}) as command:
            result = server.permissions_audit(DEVICE_ID)

        self.assertEqual(result, {"ok": True})
        command.assert_called_once_with(
            DEVICE_ID,
            "apps.permissionsAudit",
            timeout=30,
        )


if __name__ == "__main__":
    unittest.main()
