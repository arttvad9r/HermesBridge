import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"


class BatteryUsageMcpTest(unittest.TestCase):
    def test_battery_usage_maps_only_to_read_only_typed_tool(self) -> None:
        calls = []

        def fake_request(method, path, payload=None, *, timeout=15):
            calls.append((method, path, payload, timeout))
            return {
                "ok": True,
                "result": {
                    "source": "batterystats_charged_checkin",
                    "topUids": [],
                },
            }

        with patch.object(server, "_request_json", side_effect=fake_request):
            result = server.battery_usage(DEVICE_ID)

        self.assertTrue(result["ok"])
        self.assertEqual(len(calls), 1)
        method, path, payload, timeout = calls[0]
        self.assertEqual(method, "POST")
        self.assertEqual(path, f"/api/v1/devices/{DEVICE_ID}/commands")
        self.assertEqual(payload, {"tool": "battery.usage", "arguments": {}})
        self.assertEqual(timeout, 75)

    def test_battery_usage_rejects_invalid_device_before_request(self) -> None:
        with patch.object(server, "_request_json") as request:
            with self.assertRaises(ValueError):
                server.battery_usage("../phone")
            request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
