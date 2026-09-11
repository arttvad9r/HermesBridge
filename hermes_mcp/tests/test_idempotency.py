import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"
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
