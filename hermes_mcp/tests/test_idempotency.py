import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"
OTHER_DEVICE_ID = "device_223e4567-e89b-12d3-a456-426614174000"
IDEMPOTENCY_KEY = "retry:123e4567-e89b-12d3-a456-426614174000"


class IdempotencyKeyTest(unittest.TestCase):
    def test_device_command_forwards_valid_idempotency_key(self) -> None:
        calls = []

        def fake_request(method, path, payload=None, **kwargs):
            calls.append((method, path, payload, kwargs))
            return {"ok": True, "requestId": IDEMPOTENCY_KEY}

        with patch.object(server, "_request_json", side_effect=fake_request):
            result = server._device_command(
                DEVICE_ID,
                "apps.forceStop",
                {"packageName": "com.example.app"},
                idempotency_key=IDEMPOTENCY_KEY,
            )

        self.assertTrue(result["ok"])
        self.assertEqual(
            calls[0][2],
            {
                "tool": "apps.forceStop",
                "arguments": {"packageName": "com.example.app"},
                "idempotencyKey": IDEMPOTENCY_KEY,
            },
        )

    def test_invalid_idempotency_key_is_rejected_before_request(self) -> None:
        with patch.object(server, "_request_json") as request:
            for key in ("", "contains space", "x" * 129, " leading"):
                with self.subTest(key=key):
                    with self.assertRaises(ValueError):
                        server.force_stop_app(
                            DEVICE_ID,
                            "com.example.app",
                            idempotency_key=key,
                        )
            request.assert_not_called()

    def test_stable_argument_mutations_forward_same_retry_identity(self) -> None:
        calls = []

        def fake_request(method, path, payload=None, **kwargs):
            calls.append((method, path, payload, kwargs))
            return {"ok": False, "error": {"code": "approval_required"}}

        with patch.object(server, "_request_json", side_effect=fake_request):
            server.force_stop_app(
                DEVICE_ID,
                "com.example.app",
                idempotency_key=IDEMPOTENCY_KEY,
            )
            server.uninstall_app(
                DEVICE_ID,
                "com.example.app",
                keep_data=True,
                idempotency_key=IDEMPOTENCY_KEY,
            )
            server.revoke_app_permission(
                DEVICE_ID,
                "com.example.app",
                "android.permission.CAMERA",
                idempotency_key=IDEMPOTENCY_KEY,
            )
            server.delete_path(
                DEVICE_ID,
                ["Disposable", "target.txt"],
                idempotency_key=IDEMPOTENCY_KEY,
            )

        self.assertEqual(len(calls), 4)
        for _, _, payload, _ in calls:
            self.assertEqual(payload["idempotencyKey"], IDEMPOTENCY_KEY)

    def test_install_forwards_retry_identity_with_device_scoped_staging_key(self) -> None:
        staged = {
            "artifactId": "apk_123e4567-e89b-12d3-a456-426614174000",
            "downloadToken": "A" * 43,
            "fileName": "example.apk",
            "sizeBytes": 1234,
            "sha256": "a" * 64,
        }
        calls = []

        def fake_device_command(device_id, tool, arguments=None, **kwargs):
            calls.append((device_id, tool, arguments, kwargs))
            return {"ok": False, "error": {"code": "approval_required"}}

        with (
            patch.object(server, "_stage_apk", return_value=staged) as stage,
            patch.object(server, "_device_command", side_effect=fake_device_command),
        ):
            result = server.install_apk(
                DEVICE_ID,
                "example.apk",
                replace=False,
                idempotency_key=IDEMPOTENCY_KEY,
            )

        self.assertFalse(result["ok"])
        stage.assert_called_once_with(
            "example.apk",
            staging_key=server._install_staging_key(DEVICE_ID, IDEMPOTENCY_KEY),
        )
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][0], DEVICE_ID)
        self.assertEqual(calls[0][1], "apps.install")
        self.assertEqual(calls[0][2]["artifactId"], staged["artifactId"])
        self.assertEqual(calls[0][2]["downloadToken"], staged["downloadToken"])
        self.assertEqual(calls[0][2]["replace"], False)
        self.assertEqual(
            calls[0][3],
            {"timeout": 300, "idempotency_key": IDEMPOTENCY_KEY},
        )

    def test_install_staging_identity_is_device_scoped(self) -> None:
        self.assertNotEqual(
            server._install_staging_key(DEVICE_ID, IDEMPOTENCY_KEY),
            server._install_staging_key(OTHER_DEVICE_ID, IDEMPOTENCY_KEY),
        )

    def test_invalid_install_key_is_rejected_before_staging(self) -> None:
        with patch.object(server, "_stage_apk") as stage:
            with self.assertRaises(ValueError):
                server.install_apk(
                    DEVICE_ID,
                    "example.apk",
                    idempotency_key="contains space",
                )
            stage.assert_not_called()

    def test_calls_without_key_remain_backward_compatible(self) -> None:
        calls = []

        def fake_request(method, path, payload=None, **kwargs):
            calls.append(payload)
            return {"ok": False, "error": {"code": "approval_required"}}

        with patch.object(server, "_request_json", side_effect=fake_request):
            server.force_stop_app(DEVICE_ID, "com.example.app")

        self.assertEqual(
            calls,
            [{"tool": "apps.forceStop", "arguments": {"packageName": "com.example.app"}}],
        )


if __name__ == "__main__":
    unittest.main()
