import base64
import unittest
from unittest.mock import patch

from typeddroid_mcp import server

DEVICE_ID = "device_12345678-1234-1234-1234-1234567890ab"


class FsToolsMcpTest(unittest.TestCase):
    def test_read_only_fs_tools_map_to_fixed_android_tools(self) -> None:
        with patch.object(server, "_device_command", return_value={"ok": True}) as command:
            server.fs_stat(DEVICE_ID, "/data/local/tmp/a")
            command.assert_called_with(DEVICE_ID, "fs.stat", {"path": "/data/local/tmp/a"})
            server.fs_read(DEVICE_ID, "/data/local/tmp/a", 10, 1024)
            command.assert_called_with(
                DEVICE_ID, "fs.read", {"path": "/data/local/tmp/a", "offset": 10, "maxBytes": 1024}, timeout=45
            )
            server.fs_search(DEVICE_ID, "/data/local/tmp", "apk", 3)
            command.assert_called_with(
                DEVICE_ID, "fs.search", {"path": "/data/local/tmp", "query": "apk", "maxDepth": 3}, timeout=45
            )

    def test_write_validates_base64_and_forwards_idempotency(self) -> None:
        payload = base64.b64encode(b"hello").decode("ascii")
        with patch.object(server, "_device_command", return_value={"ok": True}) as command:
            server.fs_write(
                DEVICE_ID, "/data/local/tmp/a", payload, create_parents=True, idempotency_key="fs-write-1"
            )
            command.assert_called_with(
                DEVICE_ID,
                "fs.write",
                {
                    "path": "/data/local/tmp/a",
                    "dataBase64": payload,
                    "offset": 0,
                    "createParents": True,
                },
                timeout=45,
                idempotency_key="fs-write-1",
            )

    def test_invalid_fs_arguments_fail_before_request(self) -> None:
        with patch.object(server, "_device_command") as command:
            with self.assertRaises(ValueError):
                server.fs_read(DEVICE_ID, "relative/path")
            with self.assertRaises(ValueError):
                server.fs_write(DEVICE_ID, "/data/local/tmp/a", "not base64!")
            with self.assertRaises(ValueError):
                server.fs_write(DEVICE_ID, "/data/local/tmp/a", base64.b64encode(b"x" * (server.MAX_FS_WRITE_BYTES + 1)).decode())
            with self.assertRaises(ValueError):
                server.fs_search(DEVICE_ID, "/data/local/tmp", "x", server.MAX_FS_SEARCH_DEPTH + 1)
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
