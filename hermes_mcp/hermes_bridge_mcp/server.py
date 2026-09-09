from __future__ import annotations

import http.client
import json
import os
import re
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

from mcp.server import MCPServer


DEVICE_ID_RE = re.compile(r"^device_[A-Za-z0-9-]+$")
PACKAGE_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$")
ARTIFACT_ID_RE = re.compile(r"^apk_[0-9a-fA-F-]{36}$")
ARTIFACT_TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{40,128}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
DEFAULT_RELAY_URL = "http://127.0.0.1:8080"
DEFAULT_APK_DIR = "/opt/HermesBridge/apks"
LOOPBACK_HOSTS = {"127.0.0.1", "::1", "localhost"}
MAX_PATH_DEPTH = 32
MAX_PATH_SEGMENT_LENGTH = 255
MAX_PACKAGE_NAME_LENGTH = 255
MAX_APK_NAME_LENGTH = 120
MAX_APK_BYTES = 200 * 1024 * 1024
HERMES_BRIDGE_PACKAGE = "io.github.arttvad9r.hermesbridge"
APK_NAME_HEADER = "X-Hermes-Apk-Name"

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


def _apk_directory() -> Path:
    value = os.environ.get("HERMES_BRIDGE_APK_DIR", DEFAULT_APK_DIR).strip()
    if not value:
        raise RuntimeError("HERMES_BRIDGE_APK_DIR must not be empty.")
    return Path(value).expanduser().resolve()


def _request_json(
    method: str,
    path: str,
    payload: dict[str, Any] | None = None,
    *,
    timeout: int = 15,
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
        with urlopen(request, timeout=timeout) as response:
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


def _device_command(
    device_id: str,
    tool: str,
    arguments: dict[str, Any] | None = None,
    *,
    timeout: int = 15,
) -> dict[str, Any]:
    if not DEVICE_ID_RE.fullmatch(device_id):
        raise ValueError("device_id has an invalid format.")

    result = _request_json(
        "POST",
        f"/api/v1/devices/{quote(device_id, safe='')}/commands",
        {"tool": tool, "arguments": arguments or {}},
        timeout=timeout,
    )
    if not isinstance(result, dict):
        raise RuntimeError("Relay returned an invalid command response.")
    return result


def _validate_path_segments(path_segments: list[str]) -> None:
    if len(path_segments) > MAX_PATH_DEPTH:
        raise ValueError("path_segments exceeds the maximum depth.")
    for segment in path_segments:
        if not isinstance(segment, str):
            raise ValueError("path_segments must contain only strings.")
        if not segment or len(segment) > MAX_PATH_SEGMENT_LENGTH:
            raise ValueError("path_segments contains an invalid segment length.")
        if segment in {".", ".."} or "/" in segment or "\\" in segment or "\x00" in segment:
            raise ValueError("path_segments contains a forbidden segment.")


def _validate_package_name(package_name: str, *, protect_bridge: bool = True) -> str:
    if not isinstance(package_name, str):
        raise ValueError("package_name must be a string.")
    value = package_name.strip()
    if not (3 <= len(value) <= MAX_PACKAGE_NAME_LENGTH) or not PACKAGE_NAME_RE.fullmatch(value):
        raise ValueError("package_name is not a valid Android package name.")
    if protect_bridge and value == HERMES_BRIDGE_PACKAGE:
        raise ValueError("The Hermes Bridge package is protected from agent package operations.")
    return value


def _resolve_apk_file(apk_name: str) -> Path:
    if not isinstance(apk_name, str):
        raise ValueError("apk_name must be a string.")
    name = apk_name.strip()
    if not (5 <= len(name) <= MAX_APK_NAME_LENGTH):
        raise ValueError("apk_name has an invalid length.")
    if not name.lower().endswith(".apk"):
        raise ValueError("apk_name must end in .apk.")
    if any(ord(char) < 32 or char in "/\\" for char in name):
        raise ValueError("apk_name must be a simple file name without path separators.")

    root = _apk_directory()
    candidate = (root / name).resolve(strict=True)
    if candidate.parent != root or not candidate.is_file():
        raise ValueError("apk_name must identify a file directly inside HERMES_BRIDGE_APK_DIR.")
    size = candidate.stat().st_size
    if not (1 <= size <= MAX_APK_BYTES):
        raise ValueError(f"APK size must be between 1 and {MAX_APK_BYTES} bytes.")
    return candidate


def _stage_apk(apk_name: str) -> dict[str, Any]:
    apk_path = _resolve_apk_file(apk_name)
    size_bytes = apk_path.stat().st_size
    parsed = urlsplit(_relay_url())
    connection_type = (
        http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
    )
    connection = connection_type(parsed.hostname, parsed.port, timeout=120)

    try:
        connection.putrequest("POST", "/api/v1/apk-artifacts")
        connection.putheader("Authorization", f"Bearer {_admin_token()}")
        connection.putheader("Accept", "application/json")
        connection.putheader("Content-Type", "application/vnd.android.package-archive")
        connection.putheader("Content-Length", str(size_bytes))
        connection.putheader(APK_NAME_HEADER, apk_path.name)
        connection.endheaders()

        with apk_path.open("rb") as source:
            while chunk := source.read(64 * 1024):
                connection.send(chunk)

        response = connection.getresponse()
        raw = response.read(1024 * 1024)
        if response.status != 201:
            detail = raw.decode("utf-8", errors="replace")
            raise RuntimeError(f"Relay APK staging returned HTTP {response.status}: {detail}")
    except OSError as exc:
        raise RuntimeError(f"Relay APK staging failed: {exc}") from exc
    finally:
        connection.close()

    try:
        result = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise RuntimeError("Relay returned invalid APK staging JSON.") from exc
    if not isinstance(result, dict):
        raise RuntimeError("Relay returned an invalid APK staging response.")

    artifact_id = result.get("artifactId")
    token = result.get("downloadToken")
    file_name = result.get("fileName")
    staged_size = result.get("sizeBytes")
    sha256 = result.get("sha256")
    if not isinstance(artifact_id, str) or not ARTIFACT_ID_RE.fullmatch(artifact_id):
        raise RuntimeError("Relay returned an invalid APK artifact ID.")
    if not isinstance(token, str) or not ARTIFACT_TOKEN_RE.fullmatch(token):
        raise RuntimeError("Relay returned an invalid APK artifact token.")
    if file_name != apk_path.name:
        raise RuntimeError("Relay changed the staged APK file name unexpectedly.")
    if staged_size != size_bytes:
        raise RuntimeError("Relay returned an unexpected staged APK size.")
    if not isinstance(sha256, str) or not SHA256_RE.fullmatch(sha256):
        raise RuntimeError("Relay returned an invalid APK SHA-256.")
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
def battery_usage(device_id: str) -> dict[str, Any]:
    """Read parsed battery power usage since the last charge from Android batterystats. This is read-only and exposes no shell arguments."""
    return _device_command(device_id, "battery.usage", timeout=75)


@mcp.tool()
def list_apps(device_id: str) -> dict[str, Any]:
    """List launcher-visible apps on one paired Android phone without broad package access."""
    return _device_command(device_id, "apps.list")


@mcp.tool()
def app_usage(device_id: str, days: int = 30) -> dict[str, Any]:
    """Read bounded Android UsageStats for launcher-visible apps over 1-365 days. Requires Usage Access granted on the phone."""
    if isinstance(days, bool) or not isinstance(days, int) or not 1 <= days <= 365:
        raise ValueError("days must be an integer from 1 to 365.")
    return _device_command(
        device_id,
        "apps.usage",
        {"days": days},
    )


@mcp.tool()
def app_permissions(device_id: str, package_name: str) -> dict[str, Any]:
    """Read requested and currently granted permissions for one launcher-visible Android app. Does not broaden package visibility or modify permissions."""
    package = _validate_package_name(package_name, protect_bridge=False)
    return _device_command(
        device_id,
        "apps.permissions",
        {"packageName": package},
    )


@mcp.tool()
def permissions_audit(device_id: str) -> dict[str, Any]:
    """Audit launcher-visible apps for Android permissions that are both granted and classified by the platform as dangerous. The result is bounded and read-only."""
    return _device_command(device_id, "apps.permissionsAudit", timeout=30)


@mcp.tool()
def list_files(
    device_id: str,
    path_segments: list[str] | None = None,
) -> dict[str, Any]:
    """List one directory inside the folder explicitly granted in the Android app."""
    segments = list(path_segments or [])
    _validate_path_segments(segments)
    return _device_command(
        device_id,
        "files.list",
        {"pathSegments": segments},
    )


@mcp.tool()
def analyze_files(
    device_id: str,
    path_segments: list[str] | None = None,
) -> dict[str, Any]:
    """Analyze storage inside a granted SAF directory, returning bounded totals and the largest files without modifying anything."""
    segments = list(path_segments or [])
    _validate_path_segments(segments)
    return _device_command(
        device_id,
        "files.analyze",
        {"pathSegments": segments},
        timeout=75,
    )


@mcp.tool()
def delete_path(device_id: str, path_segments: list[str]) -> dict[str, Any]:
    """Request deletion of one file or directory inside the granted SAF tree. The phone requires explicit local approval."""
    segments = list(path_segments)
    _validate_path_segments(segments)
    if not segments:
        raise ValueError("path_segments must identify a target below the granted root.")
    return _device_command(
        device_id,
        "files.delete",
        {"pathSegments": segments},
    )


@mcp.tool()
def install_apk(
    device_id: str,
    apk_name: str,
    replace: bool = True,
) -> dict[str, Any]:
    """Stage one APK from HERMES_BRIDGE_APK_DIR and request installation. The phone verifies the APK and requires explicit local approval."""
    if not isinstance(replace, bool):
        raise ValueError("replace must be a boolean.")
    staged = _stage_apk(apk_name)
    return _device_command(
        device_id,
        "apps.install",
        {
            "artifactId": staged["artifactId"],
            "downloadToken": staged["downloadToken"],
            "fileName": staged["fileName"],
            "sizeBytes": staged["sizeBytes"],
            "sha256": staged["sha256"],
            "replace": replace,
        },
        timeout=300,
    )


@mcp.tool()
def uninstall_app(
    device_id: str,
    package_name: str,
    keep_data: bool = False,
) -> dict[str, Any]:
    """Request uninstall of one Android package. The phone requires explicit local approval before execution."""
    package = _validate_package_name(package_name)
    if not isinstance(keep_data, bool):
        raise ValueError("keep_data must be a boolean.")
    return _device_command(
        device_id,
        "apps.uninstall",
        {
            "packageName": package,
            "keepData": keep_data,
        },
    )


@mcp.tool()
def force_stop_app(device_id: str, package_name: str) -> dict[str, Any]:
    """Request a privileged force-stop of one Android package. The phone requires explicit local approval."""
    package = _validate_package_name(package_name)
    return _device_command(
        device_id,
        "apps.forceStop",
        {"packageName": package},
    )


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
