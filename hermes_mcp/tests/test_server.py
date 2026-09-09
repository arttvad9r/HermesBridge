import os
import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


class HermesBridgeMcpTest(unittest.TestCase):
    def test_relay_defaults_to_loopback(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(server._relay_url(), "http://127.0.0.1:8080")

    def test_plaintext_remote_relay_is_rejected(self) -> None:
        with patch.dict(
            os.environ,
            {"HERMES_BRIDGE_RELAY_URL": "http://example.com:8080"},
            clear=True,
        ):
            with self.assertRaises(RuntimeError):
                server._relay_url()

    def test_loopback_prefix_spoof_is_rejected(self) -> None:
        for url in (
            "http://localhost.evil.example:8080",
            "http://127.0.0.1.evil.example:8080",
        ):
            with self.subTest(url=url), patch.dict(
                os.environ,
                {"HERMES_BRIDGE_RELAY_URL": url},
                clear=True,
            ):
                with self.assertRaises(RuntimeError):
                    server._relay_url()

    def test_device_health_cannot_select_arbitrary_tool(self) -> None:
        calls = []

        def fake_request(method, path, payload=None):
            calls.append((method, path, payload))
            return {"ok": True, "result": {"batteryPercent": 80}}

        with patch.object(server, "_request_json", side_effect=fake_request):
            result = server.device_health("device_123e4567-e89b-12d3-a456-426614174000")

        self.assertTrue(result["ok"])
        self.assertEqual(len(calls), 1)
        method, path, payload = calls[0]
        self.assertEqual(method, "POST")
        self.assertEqual(
            path,
            "/api/v1/devices/device_123e4567-e89b-12d3-a456-426614174000/commands",
        )
        self.assertEqual(payload, {"tool": "device.health", "arguments": {}})

    def test_invalid_device_id_is_rejected_before_request(self) -> None:
        with patch.object(server, "_request_json") as request:
            with self.assertRaises(ValueError):
                server.device_health("../other")
            request.assert_not_called()

    def test_short_admin_token_is_rejected(self) -> None:
        with patch.dict(
            os.environ,
            {"HERMES_BRIDGE_ADMIN_TOKEN": "too-short"},
            clear=True,
        ):
            with self.assertRaises(RuntimeError):
                server._admin_token()


if __name__ == "__main__":
    unittest.main()
