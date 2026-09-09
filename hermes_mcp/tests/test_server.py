import os
import unittest
from unittest.mock import patch

from hermes_bridge_mcp import server


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"


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
            result = server.device_health(DEVICE_ID)

        self.assertTrue(result["ok"])
        self.assertEqual(len(calls), 1)
        method, path, payload = calls[0]
        self.assertEqual(method, "POST")
        self.assertEqual(path, f"/api/v1/devices/{DEVICE_ID}/commands")
        self.assertEqual(payload, {"tool": "device.health", "arguments": {}})

    def test_list_apps_maps_only_to_apps_list(self) -> None:
        calls = []

        def fake_request(method, path, payload=None):
            calls.append((method, path, payload))
            return {"ok": True, "result": {"count": 1, "apps": []}}

        with patch.object(server, "_request_json", side_effect=fake_request):
            result = server.list_apps(DEVICE_ID)

        self.assertTrue(result["ok"])
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][2], {"tool": "apps.list", "arguments": {}})

    def test_list_files_maps_only_to_scoped_files_list(self) -> None:
        calls = []

        def fake_request(method, path, payload=None):
            calls.append((method, path, payload))
            return {"ok": True, "result": {"count": 1, "entries": []}}

        with patch.object(server, "_request_json", side_effect=fake_request):
            result = server.list_files(DEVICE_ID, ["Documents", "Notes"])

        self.assertTrue(result["ok"])
        self.assertEqual(len(calls), 1)
        self.assertEqual(
            calls[0][2],
            {
                "tool": "files.list",
                "arguments": {"pathSegments": ["Documents", "Notes"]},
            },
        )

    def test_list_files_rejects_traversal_before_request(self) -> None:
        with patch.object(server, "_request_json") as request:
            with self.assertRaises(ValueError):
                server.list_files(DEVICE_ID, [".."])
            request.assert_not_called()

    def test_uninstall_app_maps_only_to_approved_typed_tool(self) -> None:
        calls = []

        def fake_request(method, path, payload=None):
            calls.append((method, path, payload))
            return {"ok": False, "error": {"code": "approval_required"}}

        with patch.object(server, "_request_json", side_effect=fake_request):
            result = server.uninstall_app(DEVICE_ID, "com.example.app", keep_data=True)

        self.assertFalse(result["ok"])
        self.assertEqual(len(calls), 1)
        self.assertEqual(
            calls[0][2],
            {
                "tool": "apps.uninstall",
                "arguments": {
                    "packageName": "com.example.app",
                    "keepData": True,
                },
            },
        )

    def test_uninstall_app_rejects_invalid_package_before_request(self) -> None:
        with patch.object(server, "_request_json") as request:
            for package_name in ("../other", "com/example/app", "single", ""):
                with self.subTest(package_name=package_name):
                    with self.assertRaises(ValueError):
                        server.uninstall_app(DEVICE_ID, package_name)
            request.assert_not_called()

    def test_uninstall_app_rejects_bridge_self_uninstall(self) -> None:
        with patch.object(server, "_request_json") as request:
            with self.assertRaises(ValueError):
                server.uninstall_app(DEVICE_ID, server.HERMES_BRIDGE_PACKAGE)
            request.assert_not_called()

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
