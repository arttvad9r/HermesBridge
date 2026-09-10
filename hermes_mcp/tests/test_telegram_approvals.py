import io
import json
import os
import unittest
from unittest.mock import patch

from hermes_bridge_mcp import telegram_approvals as notifier


DEVICE_ID = "device_123e4567-e89b-12d3-a456-426614174000"
APPROVAL_ID = "approval_123e4567-e89b-12d3-a456-426614174000"
OTHER_APPROVAL_ID = "approval_223e4567-e89b-12d3-a456-426614174000"
BOT_TOKEN = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi"
CHAT_ID = -1001234567890


class TelegramApprovalNotifierTest(unittest.TestCase):
    def config(self) -> notifier.TelegramApprovalNotifierConfig:
        return notifier.TelegramApprovalNotifierConfig(
            bot_token=BOT_TOKEN,
            chat_id=CHAT_ID,
            device_id=DEVICE_ID,
            poll_seconds=10,
        )

    def notice(self, expires_at=20_000, approval_id=APPROVAL_ID):
        return notifier.PendingApprovalNotice(
            approval_id=approval_id,
            tool="apps.forceStop",
            risk="PRIVILEGED",
            expires_at_epoch_millis=expires_at,
        )

    def test_normalize_accepts_only_expected_device_tool_risk_and_shape(self):
        value = {
            "deviceId": DEVICE_ID,
            "approvalId": APPROVAL_ID,
            "tool": "apps.forceStop",
            "risk": "PRIVILEGED",
            "expiresAtEpochMillis": 20_000,
        }
        self.assertEqual(
            self.notice(),
            notifier.normalize_pending_approval_notice(value, DEVICE_ID),
        )

        wrong_risk = dict(value, risk="MUTATING")
        wrong_device = dict(value, deviceId="device_other")
        extra_tool = dict(value, tool="run_shell", risk="PRIVILEGED")
        self.assertIsNone(
            notifier.normalize_pending_approval_notice(wrong_risk, DEVICE_ID)
        )
        self.assertIsNone(
            notifier.normalize_pending_approval_notice(wrong_device, DEVICE_ID)
        )
        self.assertIsNone(
            notifier.normalize_pending_approval_notice(extra_tool, DEVICE_ID)
        )

    def test_message_contains_only_allowlisted_metadata_and_no_approval_id(self):
        message = notifier.format_telegram_approval_message(
            self.notice(),
            now_epoch_millis=10_000,
        )

        self.assertIn("apps.forceStop", message)
        self.assertIn("PRIVILEGED", message)
        self.assertIn("10 сек", message)
        self.assertIn("не может подтвердить действие", message)
        self.assertNotIn(APPROVAL_ID, message)
        self.assertNotIn(DEVICE_ID, message)

    def test_notify_deduplicates_until_expiry_and_retries_failed_send(self):
        state = notifier.TelegramApprovalNotifierState()
        sent = []

        def fetch(_device_id):
            return [self.notice()]

        def send(_config, text):
            sent.append(text)

        self.assertEqual(
            1,
            notifier.notify_pending_approvals_once(
                self.config(),
                state,
                now_epoch_millis=10_000,
                fetch_notices=fetch,
                send_message=send,
            ),
        )
        self.assertEqual(
            0,
            notifier.notify_pending_approvals_once(
                self.config(),
                state,
                now_epoch_millis=11_000,
                fetch_notices=fetch,
                send_message=send,
            ),
        )
        self.assertEqual(1, len(sent))

        retry_state = notifier.TelegramApprovalNotifierState()
        attempts = []

        def failing_send(_config, _text):
            attempts.append("failed")
            raise RuntimeError("send failed")

        with self.assertRaises(RuntimeError):
            notifier.notify_pending_approvals_once(
                self.config(),
                retry_state,
                now_epoch_millis=10_000,
                fetch_notices=fetch,
                send_message=failing_send,
            )
        self.assertNotIn(APPROVAL_ID, retry_state.seen_until_epoch_millis)
        self.assertEqual(["failed"], attempts)

    def test_expired_notice_is_not_sent(self):
        state = notifier.TelegramApprovalNotifierState()
        sent = []

        count = notifier.notify_pending_approvals_once(
            self.config(),
            state,
            now_epoch_millis=20_000,
            fetch_notices=lambda _device: [self.notice(expires_at=20_000)],
            send_message=lambda _config, text: sent.append(text),
        )

        self.assertEqual(0, count)
        self.assertEqual([], sent)

    def test_notice_beyond_relay_maximum_ttl_is_rejected(self):
        state = notifier.TelegramApprovalNotifierState()
        sent = []
        now = 10_000
        too_far = now + notifier.MAX_APPROVAL_TTL_MILLIS + 1

        with self.assertRaises(RuntimeError):
            notifier.notify_pending_approvals_once(
                self.config(),
                state,
                now_epoch_millis=now,
                fetch_notices=lambda _device: [self.notice(expires_at=too_far)],
                send_message=lambda _config, text: sent.append(text),
            )

        self.assertEqual([], sent)
        self.assertNotIn(APPROVAL_ID, state.seen_until_epoch_millis)

    def test_invalid_batch_is_rejected_before_any_message_is_sent(self):
        state = notifier.TelegramApprovalNotifierState()
        sent = []
        now = 10_000
        invalid_expiry = now + notifier.MAX_APPROVAL_TTL_MILLIS + 1

        with self.assertRaises(RuntimeError):
            notifier.notify_pending_approvals_once(
                self.config(),
                state,
                now_epoch_millis=now,
                fetch_notices=lambda _device: [
                    self.notice(expires_at=20_000),
                    self.notice(
                        expires_at=invalid_expiry,
                        approval_id=OTHER_APPROVAL_ID,
                    ),
                ],
                send_message=lambda _config, text: sent.append(text),
            )

        self.assertEqual([], sent)
        self.assertEqual({}, state.seen_until_epoch_millis)

    def test_config_rejects_unsafe_values_before_network_use(self):
        base_env = {
            "HERMES_BRIDGE_TELEGRAM_BOT_TOKEN": BOT_TOKEN,
            "HERMES_BRIDGE_TELEGRAM_CHAT_ID": str(CHAT_ID),
            "HERMES_BRIDGE_TELEGRAM_DEVICE_ID": DEVICE_ID,
            "HERMES_BRIDGE_TELEGRAM_POLL_SECONDS": "10",
        }
        with patch.dict(os.environ, base_env, clear=True):
            config = notifier.TelegramApprovalNotifierConfig.from_env()
            self.assertEqual(CHAT_ID, config.chat_id)
            self.assertEqual(10, config.poll_seconds)

        for key, value in (
            ("HERMES_BRIDGE_TELEGRAM_BOT_TOKEN", BOT_TOKEN + "/x"),
            ("HERMES_BRIDGE_TELEGRAM_CHAT_ID", "bad chat"),
            ("HERMES_BRIDGE_TELEGRAM_CHAT_ID", "@public_channel"),
            ("HERMES_BRIDGE_TELEGRAM_CHAT_ID", "0"),
            (
                "HERMES_BRIDGE_TELEGRAM_CHAT_ID",
                str(notifier.MAX_TELEGRAM_CHAT_ID_ABS + 1),
            ),
            ("HERMES_BRIDGE_TELEGRAM_DEVICE_ID", "../device"),
            ("HERMES_BRIDGE_TELEGRAM_POLL_SECONDS", "1"),
        ):
            env = dict(base_env)
            env[key] = value
            with patch.dict(os.environ, env, clear=True):
                with self.assertRaises(ValueError):
                    notifier.TelegramApprovalNotifierConfig.from_env()

    def test_telegram_transport_uses_fixed_https_host_integer_chat_and_no_callback_markup(self):
        captured = {}

        class FakeResponse:
            status = 200

            def read(self, _size):
                return b'{"ok":true}'

        class FakeConnection:
            def __init__(self, host, port, timeout):
                captured["connect"] = (host, port, timeout)

            def request(self, method, path, body=None, headers=None):
                captured["request"] = (method, path, body, headers)

            def getresponse(self):
                return FakeResponse()

            def close(self):
                captured["closed"] = True

        with patch.object(notifier.http.client, "HTTPSConnection", FakeConnection):
            notifier.send_telegram_message(self.config(), "fixed message")

        self.assertEqual((notifier.TELEGRAM_API_HOST, 443, 10), captured["connect"])
        method, path, raw_body, _headers = captured["request"]
        self.assertEqual("POST", method)
        self.assertEqual(f"/bot{BOT_TOKEN}/sendMessage", path)
        payload = json.loads(raw_body)
        self.assertEqual(CHAT_ID, payload["chat_id"])
        self.assertIsInstance(payload["chat_id"], int)
        self.assertEqual("fixed message", payload["text"])
        self.assertTrue(payload["protect_content"])
        self.assertNotIn("reply_markup", payload)
        self.assertTrue(captured["closed"])

    def test_main_logs_no_exception_or_token_detail_on_poll_failure(self):
        config = self.config()
        stderr = io.StringIO()
        with patch.object(
            notifier.TelegramApprovalNotifierConfig,
            "from_env",
            return_value=config,
        ), patch.object(
            notifier,
            "notify_pending_approvals_once",
            side_effect=[RuntimeError(f"secret {BOT_TOKEN}"), KeyboardInterrupt()],
        ), patch.object(notifier.time, "sleep", return_value=None), patch(
            "sys.stderr", stderr
        ):
            notifier.main()

        logged = stderr.getvalue()
        self.assertIn("poll failed", logged)
        self.assertNotIn(BOT_TOKEN, logged)
        self.assertNotIn("secret", logged)


if __name__ == "__main__":
    unittest.main()
