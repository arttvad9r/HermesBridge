# Third-party notices

## droid-mcp

Hermes Bridge contains a narrow adaptation of the Shizuku process-execution logic and package-uninstall behavior from **droid-mcp** by stixez.

- Upstream: https://github.com/stixez/droid-mcp
- Source commit used for the adaptation: `aeaa5b9e8e96f56ef64a7ca23d0726585f7b1103`
- Upstream components reviewed: `droid-mcp-shizuku/ShizukuShellBackend.kt` and `droid-mcp-shell-core/PackageManagerTools.kt`
- Upstream license: Apache License 2.0 (`SPDX-License-Identifier: Apache-2.0`)

The adapted Hermes Bridge implementation intentionally keeps only a fixed, typed `pm uninstall [-k] <package>` operation. The upstream generic shell surface is not registered or exposed to Hermes.

The Apache License 2.0 text is available from the upstream repository and at https://www.apache.org/licenses/LICENSE-2.0 .
