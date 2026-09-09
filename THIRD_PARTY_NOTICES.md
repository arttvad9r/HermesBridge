# Third-party notices

## droid-mcp

Hermes Bridge contains a narrow adaptation of Shizuku process-execution and typed package-management behavior from **droid-mcp** by stixez.

- Upstream: https://github.com/stixez/droid-mcp
- Source commit used for the adaptation: `aeaa5b9e8e96f56ef64a7ca23d0726585f7b1103`
- Upstream components reviewed: `droid-mcp-shizuku/ShizukuShellBackend.kt` and `droid-mcp-shell-core/PackageManagerTools.kt`
- Upstream license: Apache License 2.0 (`SPDX-License-Identifier: Apache-2.0`)

The adapted Hermes Bridge implementation intentionally keeps only fixed typed operations currently required by the product:

- `pm uninstall [-k] <package>`;
- `am force-stop <package>`.

The upstream generic shell surface is not registered or exposed to Hermes. The internal process runner is private to the Android privileged backend and receives argv only from these typed methods.

The Apache License 2.0 text is available from the upstream repository and at https://www.apache.org/licenses/LICENSE-2.0 .
