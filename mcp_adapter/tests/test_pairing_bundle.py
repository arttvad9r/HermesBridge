import base64
import json
import unittest

from typeddroid_mcp import server


class PairingBundleTest(unittest.TestCase):
    def test_bundle_round_trips_code_and_pinned_endpoints(self) -> None:
        bundle = server._pairing_bundle(
            "ABCD-2345",
            1_800_000_000_000,
            [
                {
                    "webSocketUrl": "wss://100.106.23.9:8443/ws/device",
                    "certificateSha256": "ab" * 32,
                },
                {
                    "webSocketUrl": "wss://192.168.3.4:8443/ws/device",
                    "certificateSha256": "cd" * 32,
                },
            ],
        )
        self.assertTrue(bundle.startswith("typeddroid://pair/"))
        encoded = bundle.removeprefix("typeddroid://pair/")
        encoded += "=" * ((4 - len(encoded) % 4) % 4)
        payload = json.loads(base64.urlsafe_b64decode(encoded))
        self.assertEqual(payload["v"], 1)
        self.assertEqual(payload["code"], "ABCD-2345")
        self.assertEqual(len(payload["endpoints"]), 2)
        self.assertNotIn("adminToken", payload)

    def test_bundle_rejects_invalid_code_pin_and_non_wss(self) -> None:
        good = [{"webSocketUrl": "wss://100.64.0.1:8443/ws/device", "certificateSha256": "ab" * 32}]
        with self.assertRaises(RuntimeError):
            server._pairing_bundle("bad", 1_800_000_000_000, good)
        with self.assertRaises(RuntimeError):
            server._pairing_bundle("ABCD-2345", 1_800_000_000_000, [{**good[0], "certificateSha256": "00"}])
        with self.assertRaises(RuntimeError):
            server._pairing_bundle("ABCD-2345", 1_800_000_000_000, [{**good[0], "webSocketUrl": "ws://100.64.0.1:8443/ws/device"}])


if __name__ == "__main__":
    unittest.main()
