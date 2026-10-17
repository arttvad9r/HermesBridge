#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/typeddroid-managed-runtime.XXXXXX")
cleanup() { rm -rf -- "$tmp"; }
trap cleanup EXIT

export HOME="$tmp/home"
export XDG_CONFIG_HOME="$tmp/config"
export XDG_STATE_HOME="$tmp/state"
export XDG_DATA_HOME="$tmp/data"
export TYPED_DROID_BIN_DIR="$tmp/bin"
export TYPED_DROID_INSTALL_ROOT="$tmp/install"
export TYPED_DROID_TRANSFER_DIR="$tmp/transfers"

"$repo_root/install.sh" >/dev/null
[[ -x "$tmp/bin/typeddroid-mcp" ]]
[[ -x "$tmp/install/relay-runtime/bin/relay" ]]
[[ ! -e "$tmp/bin/typeddroid-host" ]]

"$tmp/install/mcp-venv/bin/python" - <<'PY'
import json
import os
import socket
import time
from pathlib import Path
import anyio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

root = Path(os.environ["TYPED_DROID_INSTALL_ROOT"]).parent
state_path = Path(os.environ["XDG_STATE_HOME"]) / "typeddroid" / "mcp-runtime.json"
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
env = os.environ.copy()
env["TYPED_DROID_LOCAL_PORT"] = str(port)

async def verify() -> None:
    params = StdioServerParameters(command=str(Path(os.environ["TYPED_DROID_BIN_DIR"]) / "typeddroid-mcp"), env=env)
    async with stdio_client(params) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            initialized = await session.initialize()
            tools = await session.list_tools()
            devices = await session.call_tool("list_devices", {})
            assert initialized.server_info.name == "TypedDroid"
            assert len(tools.tools) >= 50
            assert not devices.is_error
            state = json.loads(state_path.read_text())
            assert state["relayPid"] is not None
            assert len(state["clients"]) == 1

    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        state = json.loads(state_path.read_text())
        if state["relayPid"] is None and state["clients"] == []:
            print("TYPEDDROID_MANAGED_MCP_E2E_OK")
            return
        await anyio.sleep(0.05)
    raise AssertionError(f"managed runtime did not clean up: {state}")

anyio.run(verify)
PY
