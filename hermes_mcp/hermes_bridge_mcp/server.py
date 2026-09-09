from __future__ import annotations

import json
import os
import re
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

from mcp.server import MCPServer


DEVICE_ID_RE = re.compile(r"^device_[A-Za-z0-9-]+$")
DEFAULT_RELAY_URL = "http://127.0.0.1:8080"
LOOPBACK_HOSTS = {"127.0.0.1", "::1", "localhost"}

mcp = MCPServer("Hermes Bridge")


def _relay_url() -> str:
    value = os.environ.get("HERMES_BRIDGE_RELAY_URL", DEFAULT_RELAY_URL).rstrip("/")
    parsed = urlsplit(value)

    if parsed.username is not None or parsed.password is not None:
        raise RuntimeError("HERMES_BRIDGE_RELAY_URL must not contain user info.")
    if parsed.query or parsed.fragment:
        raise RuntimeError("HERMES_BRIDGE_RELAY_URL must not contain query or fragment data.")
    if parsed.path not in ("", "/"):
        raise RuntimeError("HERMES_BRIDGE_RELAY_URL must not contain a path.")
    if parsed.hostname is None:
        raise RuntimeError("HERMES_BRIDGE_RELAY_URL has no valid hostname.")

    if parsed.scheme == "http":
        if parsed.hostname.lower() not in LOOPBACK_HOSTS:
            raise RuntimeError(
                "Plain HTTP relay URLs are allowed only for loopback hosts."
            )
    elif parsed.scheme != "https":
        raise RuntimeError("Relay URL must use loopback HTTP or HTTPS.")

    return value


def _admin_token() -> str:
    token = os.environ.get("HERMES_BRIDGE_ADMIN_TOKEN", "").strip()
    if len(token) < 24:
        raise RuntimeError(
            "HERMES_BRIDGE_ADMIN_TOKEN is missing or shorter than 24 characters."
        )
    return token


def _request_json(
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
) -> Any:
    body = None
    headers = {
        "Authorization": f"Bearer {_admin_token()}",
        "Accept": "application/json",
    }
    if payload is not None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"

    request = Request(
        f"{_relay_url()}{path}",
        data=body,
        headers=headers,
        method=method,
    )

    try:
        with urlopen(request, timeout=15) as response:
            raw = response.read(1024 * 1024)
    except HTTPError as exc:
        detail = exc.read(16 * 1024).decode("utf-8", errors="replace")
        raise RuntimeError(f"Relay returned HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise RuntimeError(f"Relay is unavailable: {exc.reason}") from exc

    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError("Relay returned invalid JSON.") from exc


def _device_command(device_id: str, tool: str) -> dict[str, Any]:
    if not DEVICE_ID_RE.fullmatch(device_id):
        raise ValueError("device_id has an invalid format.")

    result = _request_json(
        "POST",
        f"/api/v1/devices/{quote(device_id, safe='')}/commands",
        {"tool": tool, "arguments": {}},
    )
    if not isinstance(result, dict):
        raise RuntimeError("Relay returned an invalid command response.")
    return result


@mcp.tool()
def list_devices() -> list[dict[str, Any]]:
    """List phones paired with Hermes Bridge and whether each is currently connected."""
    result = _request_json("GET", "/api/v1/devices")
    if not isinstance(result, list):
        raise RuntimeError("Relay returned an invalid device list.")
    return result


@mcp.tool()
def create_pairing_code() -> dict[str, Any]:
    """Create a one-time pairing code for connecting a new Android phone."""
    result = _request_json("POST", "/api/v1/pairing-codes")
    if not isinstance(result, dict) or not isinstance(result.get("code"), str):
        raise RuntimeError("Relay returned an invalid pairing code response.")
    return result


@mcp.tool()
def device_health(device_id: str) -> dict[str, Any]:
    """Read battery, memory and storage health from one paired Android phone."""
    return _device_command(device_id, "device.health")


@mcp.tool()
def list_apps(device_id: str) -> dict[str, Any]:
    """List launcher-visible apps on one paired Android phone without broad package access."""
    return _device_command(device_id, "apps.list")


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
