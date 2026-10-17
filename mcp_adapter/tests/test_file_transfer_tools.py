import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from typeddroid_mcp import server

DEVICE_ID = "device_12345678-1234-1234-1234-1234567890ab"


class FileTransferToolsMcpTest(unittest.TestCase):
    def test_push_file_stages_only_transfer_directory_file_and_maps_fixed_tool(self) -> None:
        staged = {
            "artifactId": "file_123e4567-e89b-12d3-a456-426614174000",
            "downloadToken": "A" * 43,
            "fileName": "hello.bin",
            "sizeBytes": 123,
            "sha256": "a" * 64,
        }
        with (
            patch.object(server, "_stage_transfer_file", return_value=staged) as stage,
            patch.object(server, "_device_command", return_value={"ok": True}) as command,
        ):
            result = server.push_file(
                DEVICE_ID,
                "hello.bin",
                "/sdcard/Download/hello.bin",
                overwrite=True,
                create_parents=True,
            )
        self.assertTrue(result["ok"])
        stage.assert_called_once_with("hello.bin")
        command.assert_called_once_with(
            DEVICE_ID,
            "transfer.push",
            {
                "artifactId": staged["artifactId"],
                "downloadToken": staged["downloadToken"],
                "fileName": "hello.bin",
                "sizeBytes": 123,
                "sha256": "a" * 64,
                "destinationPath": "/sdcard/Download/hello.bin",
                "overwrite": True,
                "createParents": True,
            },
            timeout=650,
        )

    def test_pull_file_uses_one_time_slot_then_materializes_verified_upload(self) -> None:
        slot = {"uploadId": "upload_123e4567-e89b-12d3-a456-426614174000", "uploadToken": "B" * 43}
        local = {"localName": "phone.bin", "localPath": "/tmp/phone.bin", "sizeBytes": 42, "sha256": "b" * 64}
        with (
            patch.object(server, "_create_upload_slot", return_value=slot),
            patch.object(server, "_device_command", return_value={"ok": True, "result": {"pulled": True}}) as command,
            patch.object(server, "_materialize_upload", return_value=local) as materialize,
        ):
            result = server.pull_file(DEVICE_ID, "/data/local/tmp/phone.bin", "phone.bin")
        command.assert_called_once_with(
            DEVICE_ID,
            "transfer.pull",
            {
                "sourcePath": "/data/local/tmp/phone.bin",
                "uploadId": slot["uploadId"],
                "uploadToken": slot["uploadToken"],
            },
            timeout=650,
        )
        materialize.assert_called_once_with(slot["uploadId"], "phone.bin", False)
        self.assertEqual(result["localFile"], local)

    def test_transfer_source_is_confined_to_dedicated_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "ok.bin").write_bytes(b"hello")
            (root / "sub").mkdir()
            (root / "sub" / "nested.bin").write_bytes(b"no")
            with patch.dict(os.environ, {"TYPED_DROID_TRANSFER_DIR": directory}, clear=False):
                self.assertEqual(server._resolve_transfer_file("ok.bin"), root / "ok.bin")
                for name in ("../ok.bin", "sub/nested.bin", "", ".", ".."):
                    with self.subTest(name=name):
                        with self.assertRaises((ValueError, FileNotFoundError)):
                            server._resolve_transfer_file(name)

    def test_pull_refuses_existing_local_file_before_creating_slot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "exists.bin").write_bytes(b"existing")
            with (
                patch.dict(os.environ, {"TYPED_DROID_TRANSFER_DIR": directory}, clear=False),
                patch.object(server, "_create_upload_slot") as slot,
                patch.object(server, "_device_command") as command,
            ):
                with self.assertRaises(FileExistsError):
                    server.pull_file(DEVICE_ID, "/data/local/tmp/source.bin", "exists.bin")
                slot.assert_not_called()
                command.assert_not_called()

    def test_invalid_arguments_fail_before_staging_or_device_command(self) -> None:
        with (
            patch.object(server, "_stage_transfer_file") as stage,
            patch.object(server, "_device_command") as command,
        ):
            with self.assertRaises(ValueError):
                server.push_file(DEVICE_ID, "x.bin", "relative/path")
            with self.assertRaises(ValueError):
                server.push_file(DEVICE_ID, "x.bin", "/sdcard/x", overwrite="yes")
            with self.assertRaises(ValueError):
                server.pull_file(DEVICE_ID, "/sdcard/x", "../escape")
            with self.assertRaises(ValueError):
                server.pull_file(DEVICE_ID, "/sdcard/x", "x.bin", overwrite="yes")
        stage.assert_not_called()
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
