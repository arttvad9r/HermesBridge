import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"


class AppUsageMcpTest(unittest.TestCase):
    def test_app_usage_maps_only_to_typed_tool(self) -> None:
        calls = []

        def fake_request(method, path, payload=None, *, timeout=15):
            calls.append((method, path, payload, timeout))
            return {"ok": True, "result": {"days": 90, "apps": []}}

        with patch.object(server, "_request_json", side_effect=fake_request):
            result = server.app_usage(DEVICE_ID, 90)

        self.assertTrue(result["ok"])
        self.assertEqual(len(calls), 1)
        method, path, payload, timeout = calls[0]
        self.assertEqual(method, "POST")
        self.assertEqual(path, f"/api/v1/devices/{DEVICE_ID}/commands")
        self.assertEqual(payload, {"tool": "apps.usage", "arguments": {"days": 90}})
        self.assertEqual(timeout, 15)

    def test_app_usage_defaults_to_thirty_days(self) -> None:
        calls = []

        def fake_request(method, path, payload=None, *, timeout=15):
            calls.append(payload)
            return {"ok": True, "result": {"days": 30, "apps": []}}

        with patch.object(server, "_request_json", side_effect=fake_request):
            server.app_usage(DEVICE_ID)

        self.assertEqual(calls, [{"tool": "apps.usage", "arguments": {"days": 30}}])

    def test_app_usage_rejects_invalid_days_before_request(self) -> None:
        with patch.object(server, "_request_json") as request:
            for days in (0, 366, True, 30.5, "30"):
                with self.subTest(days=days):
                    with self.assertRaises(ValueError):
                        server.app_usage(DEVICE_ID, days)
            request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
