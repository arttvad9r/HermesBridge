import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"


class HermesBridgeFileToolsTest(unittest.TestCase):
    def test_analyze_files_maps_only_to_bounded_typed_tool(self) -> None:
        calls = []

        def fake_command(device_id, tool, arguments=None, **kwargs):
            calls.append((device_id, tool, arguments, kwargs))
            return {"ok": True, "result": {"totalBytes": 1234}}

        with patch.object(server, "_device_command", side_effect=fake_command):
            result = server.analyze_files(DEVICE_ID, ["Downloads"])

        self.assertTrue(result["ok"])
        self.assertEqual(
            calls,
            [
                (
                    DEVICE_ID,
                    "files.analyze",
                    {"pathSegments": ["Downloads"]},
                    {"timeout": 75},
                )
            ],
        )

    def test_delete_path_maps_only_to_typed_delete_tool(self) -> None:
        calls = []

        def fake_command(device_id, tool, arguments=None, **kwargs):
            calls.append((device_id, tool, arguments, kwargs))
            return {"ok": False, "error": {"code": "approval_required"}}

        with patch.object(server, "_device_command", side_effect=fake_command):
            result = server.delete_path(DEVICE_ID, ["Downloads", "old.apk"])

        self.assertFalse(result["ok"])
        self.assertEqual(
            calls,
            [
                (
                    DEVICE_ID,
                    "files.delete",
                    {"pathSegments": ["Downloads", "old.apk"]},
                    {},
                )
            ],
        )

    def test_delete_path_rejects_root_and_traversal_before_request(self) -> None:
        with patch.object(server, "_device_command") as command:
            for path in ([], [".."], ["Downloads/other"]):
                with self.subTest(path=path):
                    with self.assertRaises(ValueError):
                        server.delete_path(DEVICE_ID, path)
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
