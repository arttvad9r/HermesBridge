# Third-party notices

## droid-mcp

TypedDroid retains Apache-2.0 attribution for early typed package-management behavior reviewed/adapted from **droid-mcp** by stixez. The Shizuku API/provider and companion app are no longer runtime dependencies.

- Upstream: https://github.com/stixez/droid-mcp
- Source commit used for the adaptation: `aeaa5b9e8e96f56ef64a7ca23d0726585f7b1103`
- Upstream components reviewed: `droid-mcp-shizuku/ShizukuShellBackend.kt` and `droid-mcp-shell-core/PackageManagerTools.kt`
- Upstream license: Apache License 2.0 (`SPDX-License-Identifier: Apache-2.0`)

The adapted TypedDroid process-execution pattern is kept private and is used only by fixed typed operations required by the product:

- `pm install [-r] <service-generated-staging-path>`, after the APK has been bounded and verified by size, SHA-256, package name and signing metadata;
- `pm uninstall [-k] <package>`;
- `am force-stop <package>`;
- `dumpsys batterystats --charged --checkin` for bounded read-only battery diagnostics.

The upstream generic shell surface is not registered or exposed to any AI agent. The agent cannot provide argv for these commands.

The Batterystats checkin parser, output bounds and MCP result model are TypedDroid code based on Android's documented `dumpsys batterystats --checkin` format; they are not copied from droid-mcp.

The Apache License 2.0 text is available from the upstream repository and at https://www.apache.org/licenses/LICENSE-2.0 .
