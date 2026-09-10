import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"


class PendingApprovalsToolTest(unittest.TestCase):
    def test_pending_approvals_is_read_only_target_free_and_device_scoped(self) -> None:
        expected = [
            {
                "deviceId": DEVICE_ID,
                "approvalId": "approval_123e4567-e89b-12d3-a456-426614174000",
                "tool": "apps.forceStop",
                "risk": "PRIVILEGED",
                "expiresAtEpochMillis": 1_800_000_000_000,
            }
        ]
        calls = []

        def fake_request(method, path, payload=None, **kwargs):
            calls.append((method, path, payload, kwargs))
            return expected

        with patch.object(server, "_request_json", side_effect=fake_request):
            result = server.pending_approvals(DEVICE_ID)

        self.assertEqual(expected, result)
        self.assertNotIn("displaySummary", result[0])
        self.assertEqual(
            [("GET", f"/api/v1/devices/{DEVICE_ID}/approvals", None, {})],
            calls,
        )

    def test_pending_approvals_rejects_invalid_device_before_request(self) -> None:
        with patch.object(server, "_request_json") as request:
            with self.assertRaises(ValueError):
                server.pending_approvals("../other-device")
            request.assert_not_called()

    def test_pending_approvals_rejects_unbounded_relay_list(self) -> None:
        oversized = [{} for _ in range(server.MAX_PENDING_APPROVALS + 1)]
        with patch.object(server, "_request_json", return_value=oversized):
            with self.assertRaises(RuntimeError):
                server.pending_approvals(DEVICE_ID)


if __name__ == "__main__":
    unittest.main()
