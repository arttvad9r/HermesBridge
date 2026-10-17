from __future__ import annotations

import json
import os
import socket
import stat
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest.mock import patch

from typeddroid_mcp import local_runtime


FAKE_RELAY = r'''#!/usr/bin/env python3
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ["TYPED_DROID_ADMIN_TOKEN"]
PORT = int(os.environ["PORT"])

class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass
    def do_GET(self):
        if self.path == "/health":
            body = b"ok"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/api/v1/devices":
            if self.headers.get("Authorization") != "Bearer " + TOKEN:
                self.send_response(401)
                self.end_headers()
                return
            body = b"[]"
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
'''


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class LocalRuntimeTest(unittest.TestCase):
    def test_direct_ip_policy_accepts_private_and_cgnat_but_not_public_or_linklocal_v6(self) -> None:
        self.assertTrue(local_runtime._allowed_direct_ip("192.168.1.20"))
        self.assertTrue(local_runtime._allowed_direct_ip("10.10.0.4"))
        self.assertTrue(local_runtime._allowed_direct_ip("100.100.10.20"))
        self.assertTrue(local_runtime._allowed_direct_ip("fd00::1234"))
        self.assertFalse(local_runtime._allowed_direct_ip("8.8.8.8"))
        self.assertFalse(local_runtime._allowed_direct_ip("127.0.0.1"))
        self.assertFalse(local_runtime._allowed_direct_ip("fe80::1234"))

    def test_direct_runtime_environment_is_optional_and_fails_open(self) -> None:
        with patch.dict(os.environ, {"TYPED_DROID_DIRECT_ENABLED": "0"}, clear=True):
            self.assertEqual(local_runtime._direct_runtime_environment(), {})
        with (
            patch.dict(os.environ, {"TYPED_DROID_DIRECT_ENABLED": "1"}, clear=True),
            patch.object(local_runtime, "_discover_direct_hosts", return_value=[]),
        ):
            self.assertEqual(local_runtime._direct_runtime_environment(), {})

    def test_direct_runtime_environment_uses_private_material_without_exposing_admin_port(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            keystore = Path(directory) / "direct.p12"
            keystore.write_bytes(b"test")
            with (
                patch.dict(
                    os.environ,
                    {"TYPED_DROID_DIRECT_ENABLED": "1", "TYPED_DROID_DIRECT_PORT": "18443"},
                    clear=True,
                ),
                patch.object(local_runtime, "_discover_direct_hosts", return_value=["192.168.1.20", "100.64.1.2"]),
                patch.object(local_runtime, "_port_is_available", return_value=True),
                patch.object(local_runtime, "_ensure_direct_keystore", return_value=(keystore, "secret-password")),
            ):
                env = local_runtime._direct_runtime_environment()
        self.assertEqual(env["TYPED_DROID_DIRECT_PORT"], "18443")
        self.assertEqual(env["TYPED_DROID_DIRECT_HOSTS"], "192.168.1.20,100.64.1.2")
        self.assertEqual(env["TYPED_DROID_DIRECT_KEY_ALIAS"], local_runtime.DIRECT_KEY_ALIAS)
        self.assertNotIn("PORT", env)
        self.assertNotIn("TYPED_DROID_ADMIN_TOKEN", env)

    def test_generic_port_environment_does_not_reconfigure_managed_runtime(self) -> None:
        with patch.dict(os.environ, {"PORT": "9999"}, clear=True):
            self.assertEqual(local_runtime._port(), local_runtime.DEFAULT_PORT)
        with patch.dict(os.environ, {"TYPED_DROID_LOCAL_PORT": "43210", "PORT": "9999"}, clear=True):
            self.assertEqual(local_runtime._port(), 43210)

    def test_explicit_host_url_disables_managed_runtime(self) -> None:
        with patch.dict(os.environ, {"TYPED_DROID_HOST_URL": "https://example.test"}, clear=True):
            self.assertFalse(local_runtime.should_manage_local_runtime())
        with patch.dict(os.environ, {}, clear=True):
            self.assertTrue(local_runtime.should_manage_local_runtime())

    def test_managed_runtime_starts_authenticates_and_stops(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            relay = root / "fake-relay"
            relay.write_text(FAKE_RELAY, encoding="utf-8")
            relay.chmod(relay.stat().st_mode | stat.S_IXUSR)
            port = free_port()
            env = {
                "HOME": str(root),
                "XDG_CONFIG_HOME": str(root / "config"),
                "XDG_STATE_HOME": str(root / "state"),
                "XDG_DATA_HOME": str(root / "data"),
                "TYPED_DROID_RELAY_BIN": str(relay),
                "TYPED_DROID_LOCAL_PORT": str(port),
                "TYPED_DROID_DIRECT_ENABLED": "0",
            }
            with patch.dict(os.environ, env, clear=True):
                lease = local_runtime.LocalRuntimeLease()
                lease.start()
                self.assertTrue(local_runtime._health_ok())
                token = local_runtime._token_file().read_text(encoding="utf-8").strip()
                self.assertGreaterEqual(len(token), 24)
                self.assertTrue(local_runtime._relay_accepts_admin_token(token))
                state = json.loads((local_runtime._state_dir() / "mcp-runtime.json").read_text())
                relay_pid = state["relayPid"]
                self.assertTrue(local_runtime._pid_alive(relay_pid))
                self.assertIn(os.getpid(), state["clients"])
                self.assertEqual(os.environ["TYPED_DROID_HOST_URL"], f"http://127.0.0.1:{port}")
                lease.close()
                state = json.loads((local_runtime._state_dir() / "mcp-runtime.json").read_text())
                self.assertEqual(state["clients"], [])
                self.assertIsNone(state["relayPid"])

    def test_wrong_service_on_port_is_rejected_without_killing_it(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            relay = root / "fake-relay"
            relay.write_text(FAKE_RELAY, encoding="utf-8")
            relay.chmod(relay.stat().st_mode | stat.S_IXUSR)
            port = free_port()
            env = {
                "HOME": str(root),
                "XDG_CONFIG_HOME": str(root / "config"),
                "XDG_STATE_HOME": str(root / "state"),
                "XDG_DATA_HOME": str(root / "data"),
                "TYPED_DROID_RELAY_BIN": str(relay),
                "TYPED_DROID_LOCAL_PORT": str(port),
                "TYPED_DROID_DIRECT_ENABLED": "0",
            }
            with patch.dict(os.environ, env, clear=True):
                token = "different-token-that-is-long-enough"
                proc_env = os.environ.copy()
                proc_env["TYPED_DROID_ADMIN_TOKEN"] = token
                proc_env["PORT"] = str(port)
                import subprocess, time
                proc = subprocess.Popen([str(relay)], env=proc_env)
                try:
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline and not local_runtime._health_ok():
                        time.sleep(0.05)
                    self.assertTrue(local_runtime._health_ok())
                    with self.assertRaisesRegex(RuntimeError, "unexpected process"):
                        local_runtime.LocalRuntimeLease().start()
                    self.assertIsNone(proc.poll())
                finally:
                    proc.terminate()
                    proc.wait(timeout=5)

    def test_two_process_leases_share_relay_until_last_client_closes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            relay = root / "fake-relay"
            relay.write_text(FAKE_RELAY, encoding="utf-8")
            relay.chmod(relay.stat().st_mode | stat.S_IXUSR)
            port = free_port()
            env = {
                "HOME": str(root),
                "XDG_CONFIG_HOME": str(root / "config"),
                "XDG_STATE_HOME": str(root / "state"),
                "XDG_DATA_HOME": str(root / "data"),
                "TYPED_DROID_RELAY_BIN": str(relay),
                "TYPED_DROID_LOCAL_PORT": str(port),
                "TYPED_DROID_DIRECT_ENABLED": "0",
                "PYTHONPATH": str(Path(__file__).resolve().parents[1]),
            }
            ready = root / "child-ready"
            stop = root / "child-stop"
            child_code = (
                "import os,time\n"
                "from pathlib import Path\n"
                "from typeddroid_mcp.local_runtime import LocalRuntimeLease\n"
                "lease=LocalRuntimeLease(); lease.start()\n"
                "Path(os.environ['READY_FILE']).write_text('ready')\n"
                "while not Path(os.environ['STOP_FILE']).exists(): time.sleep(0.05)\n"
                "lease.close()\n"
            )
            import subprocess, sys, time
            child_env = os.environ.copy()
            child_env.update(env)
            child_env["READY_FILE"] = str(ready)
            child_env["STOP_FILE"] = str(stop)
            child = subprocess.Popen([sys.executable, "-c", child_code], env=child_env)
            try:
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline and not ready.exists():
                    if child.poll() is not None:
                        self.fail(f"child lease exited early with {child.returncode}")
                    time.sleep(0.05)
                self.assertTrue(ready.exists())
                with patch.dict(os.environ, env, clear=True):
                    parent = local_runtime.LocalRuntimeLease()
                    parent.start()
                    state = json.loads((local_runtime._state_dir() / "mcp-runtime.json").read_text())
                    self.assertEqual(len(state["clients"]), 2)
                    relay_pid = state["relayPid"]
                    parent.close()
                    self.assertTrue(local_runtime._pid_alive(relay_pid))
                    self.assertTrue(local_runtime._health_ok())
                stop.write_text("stop")
                child.wait(timeout=8)
                with patch.dict(os.environ, env, clear=True):
                    deadline = time.monotonic() + 4
                    while time.monotonic() < deadline and local_runtime._health_ok():
                        time.sleep(0.05)
                    self.assertFalse(local_runtime._health_ok())
            finally:
                if child.poll() is None:
                    child.terminate()
                    child.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
