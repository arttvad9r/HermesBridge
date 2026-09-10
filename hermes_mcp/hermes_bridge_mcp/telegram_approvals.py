from __future__ import annotations

import http.client
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from typing import Any, Callable
from urllib.parse import quote

from .server import DEVICE_ID_RE, MAX_PENDING_APPROVALS, _request_json


TELEGRAM_BOT_TOKEN_RE = re.compile(r"^[0-9]{5,20}:[A-Za-z0-9_-]{20,160}$")
TELEGRAM_CHAT_ID_RE = re.compile(r"^-?[1-9][0-9]{0,15}$")
APPROVAL_ID_RE = re.compile(r"^approval_[0-9a-fA-F-]{36}$")
EXPECTED_TOOL_RISKS = {
    "files.delete": "MUTATING",
    "apps.install": "MUTATING",
    "apps.uninstall": "MUTATING",
    "apps.forceStop": "PRIVILEGED",
    "apps.revokePermission": "PRIVILEGED",
}
DEFAULT_POLL_SECONDS = 10
MIN_POLL_SECONDS = 2
MAX_POLL_SECONDS = 300
MAX_APPROVAL_TTL_MILLIS = 10 * 60 * 1000
MAX_TELEGRAM_CHAT_ID_ABS = (1 << 52) - 1
TELEGRAM_API_HOST = "api.telegram.org"
MAX_TELEGRAM_RESPONSE_BYTES = 64 * 1024


@dataclass(frozen=True)
class TelegramApprovalNotifierConfig:
    bot_token: str
    chat_id: int
    device_id: str
    poll_seconds: int = DEFAULT_POLL_SECONDS

    @classmethod
    def from_env(cls) -> "TelegramApprovalNotifierConfig":
        bot_token = os.environ.get("HERMES_BRIDGE_TELEGRAM_BOT_TOKEN", "").strip()
        raw_chat_id = os.environ.get("HERMES_BRIDGE_TELEGRAM_CHAT_ID", "").strip()
        device_id = os.environ.get("HERMES_BRIDGE_TELEGRAM_DEVICE_ID", "").strip()
        raw_poll_seconds = os.environ.get(
            "HERMES_BRIDGE_TELEGRAM_POLL_SECONDS",
            str(DEFAULT_POLL_SECONDS),
        ).strip()

        if not TELEGRAM_BOT_TOKEN_RE.fullmatch(bot_token):
            raise ValueError("HERMES_BRIDGE_TELEGRAM_BOT_TOKEN has an invalid format.")
        if not TELEGRAM_CHAT_ID_RE.fullmatch(raw_chat_id):
            raise ValueError("HERMES_BRIDGE_TELEGRAM_CHAT_ID must be a numeric Telegram chat ID.")
        chat_id = int(raw_chat_id)
        if abs(chat_id) > MAX_TELEGRAM_CHAT_ID_ABS:
            raise ValueError("HERMES_BRIDGE_TELEGRAM_CHAT_ID exceeds Telegram's 52-bit ID range.")
        if not DEVICE_ID_RE.fullmatch(device_id):
            raise ValueError("HERMES_BRIDGE_TELEGRAM_DEVICE_ID has an invalid format.")
        try:
            poll_seconds = int(raw_poll_seconds)
        except ValueError as exc:
            raise ValueError(
                "HERMES_BRIDGE_TELEGRAM_POLL_SECONDS must be an integer."
            ) from exc
        if not MIN_POLL_SECONDS <= poll_seconds <= MAX_POLL_SECONDS:
            raise ValueError(
                f"HERMES_BRIDGE_TELEGRAM_POLL_SECONDS must be between "
                f"{MIN_POLL_SECONDS} and {MAX_POLL_SECONDS}."
            )
        return cls(
            bot_token=bot_token,
            chat_id=chat_id,
            device_id=device_id,
            poll_seconds=poll_seconds,
        )


@dataclass(frozen=True)
class PendingApprovalNotice:
    approval_id: str
    tool: str
    risk: str
    expires_at_epoch_millis: int


@dataclass
class TelegramApprovalNotifierState:
    seen_until_epoch_millis: dict[str, int] = field(default_factory=dict)

    def prune(self, now_epoch_millis: int) -> None:
        expired = [
            approval_id
            for approval_id, expires_at in self.seen_until_epoch_millis.items()
            if expires_at <= now_epoch_millis
        ]
        for approval_id in expired:
            self.seen_until_epoch_millis.pop(approval_id, None)


def fetch_pending_approval_notices(device_id: str) -> list[PendingApprovalNotice]:
    if not DEVICE_ID_RE.fullmatch(device_id):
        raise ValueError("device_id has an invalid format.")
    result = _request_json(
        "GET",
        f"/api/v1/devices/{quote(device_id, safe='')}/approvals",
    )
    if not isinstance(result, list) or len(result) > MAX_PENDING_APPROVALS:
        raise RuntimeError("Relay returned an invalid pending approval list.")

    notices: list[PendingApprovalNotice] = []
    for item in result:
        notice = normalize_pending_approval_notice(item, device_id)
        if notice is None:
            raise RuntimeError("Relay returned an invalid pending approval entry.")
        notices.append(notice)
    return notices


def normalize_pending_approval_notice(
    value: Any,
    expected_device_id: str,
) -> PendingApprovalNotice | None:
    if not isinstance(value, dict):
        return None
    if value.get("deviceId") != expected_device_id:
        return None

    approval_id = value.get("approvalId")
    tool = value.get("tool")
    risk = value.get("risk")
    expires_at = value.get("expiresAtEpochMillis")
    if not isinstance(approval_id, str) or not APPROVAL_ID_RE.fullmatch(approval_id):
        return None
    if not isinstance(tool, str) or EXPECTED_TOOL_RISKS.get(tool) != risk:
        return None
    if isinstance(expires_at, bool) or not isinstance(expires_at, int) or expires_at <= 0:
        return None

    return PendingApprovalNotice(
        approval_id=approval_id,
        tool=tool,
        risk=risk,
        expires_at_epoch_millis=expires_at,
    )


def format_telegram_approval_message(
    notice: PendingApprovalNotice,
    now_epoch_millis: int,
) -> str:
    remaining_seconds = max(
        0,
        (notice.expires_at_epoch_millis - now_epoch_millis + 999) // 1000,
    )
    return (
        "Hermes Bridge: требуется подтверждение на телефоне.\n"
        f"Действие: {notice.tool}\n"
        f"Риск: {notice.risk}\n"
        f"Истекает примерно через: {remaining_seconds} сек.\n"
        "Откройте Hermes Bridge на телефоне, чтобы разрешить или отклонить действие. "
        "Это Telegram-сообщение не может подтвердить действие."
    )


def send_telegram_message(
    config: TelegramApprovalNotifierConfig,
    text: str,
) -> None:
    body = json.dumps(
        {
            "chat_id": config.chat_id,
            "text": text,
            "protect_content": True,
        },
        separators=(",", ":"),
    ).encode("utf-8")
    connection = http.client.HTTPSConnection(TELEGRAM_API_HOST, 443, timeout=10)
    try:
        connection.request(
            "POST",
            f"/bot{config.bot_token}/sendMessage",
            body=body,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "Content-Length": str(len(body)),
            },
        )
        response = connection.getresponse()
        raw = response.read(MAX_TELEGRAM_RESPONSE_BYTES + 1)
        if len(raw) > MAX_TELEGRAM_RESPONSE_BYTES:
            raise RuntimeError("Telegram Bot API returned an oversized response.")
        if response.status != 200:
            raise RuntimeError(
                f"Telegram Bot API returned HTTP {response.status}."
            )
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise RuntimeError("Telegram Bot API returned invalid JSON.") from exc
        if not isinstance(payload, dict) or payload.get("ok") is not True:
            raise RuntimeError("Telegram Bot API rejected the notification.")
    finally:
        connection.close()


def notify_pending_approvals_once(
    config: TelegramApprovalNotifierConfig,
    state: TelegramApprovalNotifierState,
    *,
    now_epoch_millis: int | None = None,
    fetch_notices: Callable[[str], list[PendingApprovalNotice]] = fetch_pending_approval_notices,
    send_message: Callable[[TelegramApprovalNotifierConfig, str], None] = send_telegram_message,
) -> int:
    now = int(time.time() * 1000) if now_epoch_millis is None else now_epoch_millis
    if now < 0:
        raise ValueError("now_epoch_millis must be non-negative.")
    state.prune(now)

    notices = fetch_notices(config.device_id)
    for notice in notices:
        if notice.expires_at_epoch_millis - now > MAX_APPROVAL_TTL_MILLIS:
            raise RuntimeError("Relay returned an approval beyond the maximum TTL.")

    sent = 0
    for notice in notices:
        if notice.expires_at_epoch_millis <= now:
            continue
        if notice.approval_id in state.seen_until_epoch_millis:
            continue

        message = format_telegram_approval_message(notice, now)
        send_message(config, message)
        state.seen_until_epoch_millis[notice.approval_id] = notice.expires_at_epoch_millis
        sent += 1
    return sent


def main() -> None:
    try:
        config = TelegramApprovalNotifierConfig.from_env()
    except ValueError as error:
        raise SystemExit(str(error)) from None

    state = TelegramApprovalNotifierState()
    while True:
        try:
            notify_pending_approvals_once(config, state)
        except KeyboardInterrupt:
            return
        except Exception:
            # Never echo relay/Telegram response bodies or request URLs. In particular, the bot
            # token lives in the HTTPS request path and must not be reflected into logs.
            print(
                "Hermes Bridge Telegram approval notifier poll failed; retrying.",
                file=sys.stderr,
            )
        try:
            time.sleep(config.poll_seconds)
        except KeyboardInterrupt:
            return


if __name__ == "__main__":
    main()
