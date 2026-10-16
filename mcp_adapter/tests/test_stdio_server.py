import sys
import unittest

import anyio
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


EXPECTED_TOOLS = {
    "list_devices",
    "pending_approvals",
    "create_pairing_code",
    "device_health",
    "device_capabilities",
    "activity_start",
    "settings_get",
    "settings_put",
    "appops_get",
    "appops_set",
    "process_list",
    "logcat_read",
    "list_notifications",
    "dismiss_notification",
    "open_notification",
    "notification_action",
    "reply_notification",
    "fs_stat",
    "fs_list",
    "fs_read",
    "fs_write",
    "fs_mkdir",
    "fs_copy",
    "fs_move",
    "fs_delete",
    "fs_search",
    "battery_usage",
    "list_apps",
    "app_usage",
    "app_permissions",
    "permissions_audit",
    "list_files",
    "analyze_files",
    "revoke_app_permission",
    "delete_path",
    "install_apk",
    "uninstall_app",
    "force_stop_app",
    "open_app",
    "ui_hierarchy",
    "ui_screenshot",
    "ui_tap",
    "ui_swipe",
    "ui_long_press",
    "ui_wait_for_text",
    "ui_text",
    "ui_key",
    "run_shell",
}


class StdioMcpCompatibilityTest(unittest.TestCase):
    def test_generic_stdio_server_initializes_and_lists_expected_tools(self) -> None:
        async def run() -> None:
            parameters = StdioServerParameters(
                command=sys.executable,
                args=["-m", "typeddroid_mcp.server"],
            )
            async with stdio_client(parameters) as (read_stream, write_stream):
                async with ClientSession(read_stream, write_stream) as session:
                    initialized = await session.initialize()
                    tools = await session.list_tools()

                    self.assertEqual(initialized.server_info.name, "TypedDroid")
                    self.assertEqual({tool.name for tool in tools.tools}, EXPECTED_TOOLS)

        anyio.run(run)


if __name__ == "__main__":
    unittest.main()
