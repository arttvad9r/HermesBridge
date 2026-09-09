from __future__ import annotations

import pytest

from hermes_bridge_mcp import server


def test_app_permissions_maps_to_launcher_scoped_android_tool(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, object] = {}

    def fake_device_command(
        device_id: str,
        tool: str,
        arguments: dict[str, object] | None = None,
        *,
        timeout: int = 15,
    ) -> dict[str, object]:
        captured.update(
            device_id=device_id,
            tool=tool,
            arguments=arguments,
            timeout=timeout,
        )
        return {"ok": True}

    monkeypatch.setattr(server, "_device_command", fake_device_command)

    result = server.app_permissions("device_123", " com.example.visible ")

    assert result == {"ok": True}
    assert captured == {
        "device_id": "device_123",
        "tool": "apps.permissions",
        "arguments": {"packageName": "com.example.visible"},
        "timeout": 15,
    }


def test_app_permissions_allows_reading_bridge_package(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        server,
        "_device_command",
        lambda device_id, tool, arguments=None, *, timeout=15: {
            "tool": tool,
            "arguments": arguments,
        },
    )

    result = server.app_permissions(
        "device_123",
        server.HERMES_BRIDGE_PACKAGE,
    )

    assert result["tool"] == "apps.permissions"
    assert result["arguments"] == {"packageName": server.HERMES_BRIDGE_PACKAGE}


def test_mutating_package_tools_still_protect_bridge_package() -> None:
    with pytest.raises(ValueError, match="protected"):
        server.force_stop_app("device_123", server.HERMES_BRIDGE_PACKAGE)


def test_app_permissions_rejects_invalid_package_name() -> None:
    with pytest.raises(ValueError, match="valid Android package name"):
        server.app_permissions("device_123", "../bad")
