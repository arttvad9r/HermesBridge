from __future__ import annotations

import atexit
import ipaddress
import json
import os
import secrets
import shutil
import socket
import signal
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Iterator
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

DEFAULT_PORT = 8080
DEFAULT_DIRECT_PORT = 8443
DIRECT_KEY_ALIAS = "typeddroid-direct"
STARTUP_TIMEOUT_SECONDS = 12.0
HEALTH_POLL_SECONDS = 0.1


def _config_home() -> Path:
    value = os.environ.get("XDG_CONFIG_HOME", "").strip()
    return Path(value).expanduser() if value else Path.home() / ".config"


def _state_home() -> Path:
    value = os.environ.get("XDG_STATE_HOME", "").strip()
    return Path(value).expanduser() if value else Path.home() / ".local" / "state"


def _data_home() -> Path:
    value = os.environ.get("XDG_DATA_HOME", "").strip()
    return Path(value).expanduser() if value else Path.home() / ".local" / "share"


def _runtime_root() -> Path:
    value = os.environ.get("TYPED_DROID_INSTALL_ROOT", "").strip()
    return Path(value).expanduser() if value else _data_home() / "typeddroid"


def _relay_binary() -> Path:
    value = os.environ.get("TYPED_DROID_RELAY_BIN", "").strip()
    return Path(value).expanduser() if value else _runtime_root() / "relay-runtime" / "bin" / "relay"


def _config_dir() -> Path:
    value = os.environ.get("TYPED_DROID_CONFIG_DIR", "").strip()
    return Path(value).expanduser() if value else _config_home() / "typeddroid"


def _state_dir() -> Path:
    value = os.environ.get("TYPED_DROID_STATE_DIR", "").strip()
    return Path(value).expanduser() if value else _state_home() / "typeddroid"


def _token_file() -> Path:
    value = os.environ.get("TYPED_DROID_ADMIN_TOKEN_FILE", "").strip()
    return Path(value).expanduser() if value else _config_dir() / "admin-token"


def _transfer_dir() -> Path:
    value = os.environ.get("TYPED_DROID_TRANSFER_DIR", "").strip()
    return Path(value).expanduser() if value else _data_home() / "typeddroid" / "transfers"


def _port() -> int:
    raw = os.environ.get("TYPED_DROID_LOCAL_PORT", "").strip()
    if not raw:
        return DEFAULT_PORT
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError("TypedDroid local port must be an integer.") from exc
    if not 1 <= value <= 65535:
        raise RuntimeError("TypedDroid local port must be from 1 to 65535.")
    return value



def _direct_enabled() -> bool:
    value = os.environ.get("TYPED_DROID_DIRECT_ENABLED", "1").strip().lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    raise RuntimeError("TYPED_DROID_DIRECT_ENABLED must be true or false.")


def _direct_port() -> int:
    raw = os.environ.get("TYPED_DROID_DIRECT_PORT", "").strip()
    if not raw:
        return DEFAULT_DIRECT_PORT
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError("TypedDroid direct port must be an integer.") from exc
    if not 1 <= value <= 65535 or value == _port():
        raise RuntimeError("TypedDroid direct port must be valid and distinct from the local admin port.")
    return value


def _direct_tls_dir() -> Path:
    return _config_dir() / "direct-tls"


def _direct_keystore_path() -> Path:
    return _direct_tls_dir() / "direct-ingress.p12"


def _direct_keystore_password_file() -> Path:
    return _direct_tls_dir() / "keystore-password"


def _allowed_direct_ip(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value.split("%", 1)[0])
    except ValueError:
        return False
    if address.is_loopback or address.is_multicast or address.is_unspecified:
        return False
    if address.version == 6:
        # Link-local IPv6 requires an interface scope/zone ID, which the current hint format
        # intentionally does not carry. Only ULA is useful as an unscoped direct candidate.
        return address in ipaddress.ip_network("fc00::/7")
    return any(
        address in network
        for network in (
            ipaddress.ip_network("10.0.0.0/8"),
            ipaddress.ip_network("172.16.0.0/12"),
            ipaddress.ip_network("192.168.0.0/16"),
            ipaddress.ip_network("100.64.0.0/10"),
        )
    )


def _discover_direct_hosts() -> list[str]:
    override = os.environ.get("TYPED_DROID_DIRECT_HOSTS", "").strip()
    if override:
        result = []
        for raw in override.split(","):
            value = raw.strip()
            if value and _allowed_direct_ip(value) and value not in result:
                result.append(value)
        return result[:8]

    candidates: set[str] = set()
    ip_command = shutil.which("ip")
    if ip_command:
        try:
            completed = subprocess.run(
                [ip_command, "-j", "address", "show", "up"],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                timeout=3,
            )
            for interface in json.loads(completed.stdout):
                if str(interface.get("operstate", "")).upper() == "DOWN":
                    continue
                for info in interface.get("addr_info", []):
                    value = info.get("local")
                    if isinstance(value, str) and _allowed_direct_ip(value):
                        candidates.add(value)
        except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
            pass

    if not candidates:
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None, type=socket.SOCK_STREAM):
                value = info[4][0]
                if _allowed_direct_ip(value):
                    candidates.add(value)
        except OSError:
            pass
    return sorted(candidates)[:8]


def _port_is_available(port: int) -> bool:
    family = socket.AF_INET
    with socket.socket(family, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("0.0.0.0", port))
        except OSError:
            return False
    return True


def _ensure_private_secret(path: Path, create_value: Callable[[], str]) -> str:
    _ensure_private_dir(path.parent)
    try:
        value = path.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        value = str(create_value())
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        fd = os.open(path, flags, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(value + "\n")
    if not value:
        raise RuntimeError(f"TypedDroid secret file is empty: {path}")
    try:
        path.chmod(0o600)
    except OSError:
        pass
    return value


def _ensure_direct_keystore() -> tuple[Path, str] | None:
    keytool = shutil.which("keytool")
    if not keytool:
        return None
    tls_dir = _direct_tls_dir()
    _ensure_private_dir(tls_dir)
    password = _ensure_private_secret(_direct_keystore_password_file(), lambda: secrets.token_hex(24))
    keystore = _direct_keystore_path()
    if not keystore.is_file():
        temp = tls_dir / f".direct-ingress.{os.getpid()}.p12"
        try:
            subprocess.run(
                [
                    keytool,
                    "-genkeypair",
                    "-alias", DIRECT_KEY_ALIAS,
                    "-keyalg", "EC",
                    "-groupname", "secp256r1",
                    "-sigalg", "SHA256withECDSA",
                    "-validity", "3650",
                    "-storetype", "PKCS12",
                    "-keystore", str(temp),
                    "-storepass", password,
                    "-keypass", password,
                    "-dname", "CN=typeddroid-direct",
                    "-ext", "SAN=dns:typeddroid-direct",
                    "-noprompt",
                ],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=15,
            )
            temp.chmod(0o600)
            os.replace(temp, keystore)
        except (OSError, subprocess.SubprocessError):
            try:
                temp.unlink()
            except OSError:
                pass
            return None
    try:
        keystore.chmod(0o600)
    except OSError:
        pass
    return keystore, password


def _direct_runtime_environment() -> dict[str, str]:
    if not _direct_enabled():
        return {}
    hosts = _discover_direct_hosts()
    if not hosts:
        return {}
    port = _direct_port()
    if not _port_is_available(port):
        return {}
    material = _ensure_direct_keystore()
    if material is None:
        return {}
    keystore, password = material
    return {
        "TYPED_DROID_DIRECT_KEYSTORE": str(keystore),
        "TYPED_DROID_DIRECT_KEYSTORE_PASSWORD": password,
        "TYPED_DROID_DIRECT_KEY_ALIAS": DIRECT_KEY_ALIAS,
        "TYPED_DROID_DIRECT_PORT": str(port),
        "TYPED_DROID_DIRECT_HOSTS": ",".join(hosts),
    }

def local_host_url() -> str:
    return f"http://127.0.0.1:{_port()}"


def _health_url() -> str:
    return f"{local_host_url()}/health"


def _health_ok(timeout: float = 0.4) -> bool:
    try:
        with urlopen(_health_url(), timeout=timeout) as response:
            return response.status == 200 and response.read(16) == b"ok"
    except (OSError, URLError):
        return False


def _relay_accepts_admin_token(token: str, timeout: float = 0.5) -> bool:
    request = Request(
        f"{local_host_url()}/api/v1/devices",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.status == 200
    except HTTPError as error:
        error.close()
        return False
    except (OSError, URLError):
        return False


def _ensure_private_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    try:
        path.chmod(0o700)
    except OSError:
        pass


def _ensure_token() -> str:
    path = _token_file()
    _ensure_private_dir(path.parent)
    try:
        token = path.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        token = secrets.token_hex(32)
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
        fd = os.open(path, flags, 0o600)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(token + "\n")
        except BaseException:
            try:
                path.unlink()
            except OSError:
                pass
            raise
    if len(token) < 24:
        raise RuntimeError("TypedDroid admin token is shorter than 24 characters.")
    try:
        path.chmod(0o600)
    except OSError:
        pass
    return token


def _pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    # On Linux an exited child remains visible to kill(pid, 0) while it is a zombie waiting
    # to be reaped. Treat that state as dead so SIGTERM cleanup never stalls on our own relay.
    proc_stat = Path(f"/proc/{pid}/stat")
    try:
        raw = proc_stat.read_text(encoding="utf-8", errors="replace")
        suffix = raw.rsplit(")", 1)[1].strip().split()
        if suffix and suffix[0] == "Z":
            return False
    except (FileNotFoundError, OSError, IndexError):
        pass
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _process_identity(pid: int) -> str:
    proc_stat = Path(f"/proc/{pid}/stat")
    try:
        raw = proc_stat.read_text(encoding="utf-8", errors="replace")
        suffix = raw.rsplit(")", 1)[1].strip().split()
        # /proc/<pid>/stat field 22 is process start time. After removing pid/comm,
        # suffix[0] is field 3, so field 22 is suffix[19].
        if len(suffix) > 19:
            return f"{pid}:{suffix[19]}"
    except (FileNotFoundError, OSError, IndexError):
        pass
    return str(pid)


def _process_matches_identity(pid: int, identity: str) -> bool:
    if not _pid_alive(pid):
        return False
    current = _process_identity(pid)
    # On non-/proc platforms both values degrade to the PID string.
    return current == identity


def _load_state(path: Path) -> dict:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {"relayPid": None, "clients": []}
    clients = raw.get("clients") if isinstance(raw, dict) else None
    relay_pid = raw.get("relayPid") if isinstance(raw, dict) else None
    return {
        "relayPid": relay_pid if isinstance(relay_pid, int) else None,
        "clients": [pid for pid in clients if isinstance(pid, int)] if isinstance(clients, list) else [],
    }


def _save_state(path: Path, state: dict) -> None:
    temp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    temp.write_text(json.dumps(state, separators=(",", ":")), encoding="utf-8")
    try:
        temp.chmod(0o600)
    except OSError:
        pass
    os.replace(temp, path)


@contextmanager
def _state_lock() -> Iterator[Path]:
    # Local installer/runtime currently targets Unix-like hosts. Keep the lock in stdlib only.
    import fcntl

    state_dir = _state_dir()
    _ensure_private_dir(state_dir)
    lock_path = state_dir / "mcp-runtime.lock"
    state_path = state_dir / "mcp-runtime.json"
    with lock_path.open("a+b") as handle:
        try:
            lock_path.chmod(0o600)
        except OSError:
            pass
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield state_path
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def _clean_clients(state: dict) -> None:
    state["clients"] = sorted({pid for pid in state["clients"] if _pid_alive(pid)})


def _terminate_pid(pid: int) -> None:
    if not _pid_alive(pid):
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 3.0
    while time.monotonic() < deadline:
        if not _pid_alive(pid):
            return
        time.sleep(0.05)
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def _release_client(pid: int, spawned_process: subprocess.Popen[bytes] | None = None) -> None:
    with _state_lock() as state_path:
        state = _load_state(state_path)
        state["clients"] = [client for client in state["clients"] if client != pid]
        _clean_clients(state)
        if not state["clients"]:
            relay_pid = state.get("relayPid")
            if isinstance(relay_pid, int):
                _terminate_pid(relay_pid)
                if spawned_process is not None and spawned_process.pid == relay_pid:
                    try:
                        spawned_process.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        pass
            state["relayPid"] = None
        _save_state(state_path, state)


def _watch_parent(pid: int, identity: str) -> None:
    while _process_matches_identity(pid, identity):
        time.sleep(0.2)
    _release_client(pid)


class LocalRuntimeLease:
    def __init__(self) -> None:
        self._registered = False
        self._closed = False
        self._pid = os.getpid()
        self._spawned_process: subprocess.Popen[bytes] | None = None

    def start(self) -> None:
        if self._registered:
            return
        relay_bin = _relay_binary()
        if not relay_bin.is_file() or not os.access(relay_bin, os.X_OK):
            raise RuntimeError(
                f"TypedDroid relay runtime is unavailable at {relay_bin}. Re-run the TypedDroid installer."
            )
        token = _ensure_token()
        _ensure_private_dir(_transfer_dir())
        _ensure_private_dir(_state_dir())

        spawned: subprocess.Popen[bytes] | None = None
        with _state_lock() as state_path:
            state = _load_state(state_path)
            _clean_clients(state)
            relay_pid = state["relayPid"]
            if relay_pid is not None and not _pid_alive(relay_pid):
                relay_pid = None
                state["relayPid"] = None
            healthy = _health_ok()

            if healthy and not _relay_accepts_admin_token(token):
                raise RuntimeError("TypedDroid local port is occupied by an unexpected process.")
            if not healthy:
                if relay_pid is not None and _pid_alive(relay_pid):
                    _terminate_pid(relay_pid)
                log_path = _state_dir() / "relay.log"
                log_handle = log_path.open("ab", buffering=0)
                try:
                    log_path.chmod(0o600)
                except OSError:
                    pass
                env = os.environ.copy()
                env.update(
                    {
                        "TYPED_DROID_ADMIN_TOKEN": token,
                        "TYPED_DROID_ADMIN_TOKEN_FILE": str(_token_file()),
                        "TYPED_DROID_STATE_DIR": str(_state_dir()),
                        "TYPED_DROID_TRANSFER_DIR": str(_transfer_dir()),
                        "PORT": str(_port()),
                    }
                )
                env.update(_direct_runtime_environment())
                try:
                    spawned = subprocess.Popen(
                        [str(relay_bin)],
                        stdin=subprocess.DEVNULL,
                        stdout=log_handle,
                        stderr=log_handle,
                        env=env,
                        start_new_session=True,
                    )
                finally:
                    log_handle.close()
                state["relayPid"] = spawned.pid
                self._spawned_process = spawned

            if self._pid not in state["clients"]:
                state["clients"].append(self._pid)
            _save_state(state_path, state)
            self._registered = True

        if not _health_ok():
            deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
            while time.monotonic() < deadline and not _health_ok():
                if spawned is not None and spawned.poll() is not None:
                    break
                time.sleep(HEALTH_POLL_SECONDS)
        if not _health_ok():
            self.close()
            raise RuntimeError(f"TypedDroid local relay did not become healthy. See {_state_dir() / 'relay.log'}.")

        os.environ["TYPED_DROID_HOST_URL"] = local_host_url()
        os.environ["TYPED_DROID_ADMIN_TOKEN_FILE"] = str(_token_file())
        os.environ["TYPED_DROID_TRANSFER_DIR"] = str(_transfer_dir())
        atexit.register(self.close)

    def start_watchdog(self) -> None:
        if not self._registered:
            raise RuntimeError("TypedDroid runtime lease must be started before its watchdog.")
        if os.environ.get("TYPED_DROID_DISABLE_WATCHDOG", "").strip() == "1":
            return
        identity = _process_identity(self._pid)
        env = os.environ.copy()
        env["TYPED_DROID_DISABLE_WATCHDOG"] = "1"
        subprocess.Popen(
            [sys.executable, "-m", "typeddroid_mcp.local_runtime", "--watch", str(self._pid), identity],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=env,
            start_new_session=True,
        )

    def close(self) -> None:
        if self._closed or not self._registered:
            return
        self._closed = True
        _release_client(self._pid, self._spawned_process)
        self._registered = False


def should_manage_local_runtime() -> bool:
    explicit = (
        os.environ.get("TYPED_DROID_HOST_URL", "").strip()
        or os.environ.get("TYPED_DROID_RELAY_URL", "").strip()
    )
    return not explicit


def _cli() -> None:
    if len(sys.argv) == 4 and sys.argv[1] == "--watch":
        pid = int(sys.argv[2])
        identity = sys.argv[3]
        _watch_parent(pid, identity)
        return
    raise SystemExit("usage: python -m typeddroid_mcp.local_runtime --watch <pid> <identity>")


if __name__ == "__main__":
    _cli()
