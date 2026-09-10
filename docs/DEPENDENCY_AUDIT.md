# Dependency and build-tool security audit

Audit snapshot: 2026-09-10.

This file records security-relevant dependency decisions for Hermes Bridge. It is a point-in-time review, not a claim that a dependency can never receive a future advisory. Weekly Dependabot checks cover Gradle/Maven, Python and GitHub Actions dependencies; changes still require review and CI rather than automatic merging.

## Build toolchain

### Gradle

Previous project/CI baseline: Gradle 8.12.

Findings:

- GHSA-465q-w4mf-4f4r / CVE-2025-27148 affected Gradle 8.12 and was fixed in 8.12.1.
- GHSA-w78c-w6vf-rw82 and GHSA-mqwm-5m85-gmcv affected Gradle versions below 8.14.4 by allowing failed repositories to remain eligible for fallback dependency resolution in security-sensitive failure cases.
- Gradle 8.14.4 release notes explicitly state that the release addresses both 2026 repository-resolution vulnerabilities.

Remediation:

- Hermes Bridge uses Gradle 8.14.4.
- The repository contains a standard Gradle Wrapper and CI uses `./gradlew` instead of a runner-provided Gradle binary.
- `gradle-wrapper.properties` pins the official 8.14.4 binary distribution SHA-256.
- `gradle/actions/setup-gradle` wrapper validation recognizes the committed wrapper JAR as a known Gradle wrapper.
- Dependency repositories are centralized in `settings.gradle.kts`; project-level repositories are rejected with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.
- Runtime dependencies are resolved from Google Maven and Maven Central only.

References:

- https://github.com/gradle/gradle/security/advisories/GHSA-465q-w4mf-4f4r
- https://github.com/gradle/gradle/security/advisories/GHSA-w78c-w6vf-rw82
- https://github.com/gradle/gradle/security/advisories/GHSA-mqwm-5m85-gmcv
- https://docs.gradle.org/8.14.4/release-notes.html

### Kotlin Gradle Plugin and R8

Previous baseline: Kotlin 2.1.20.

Findings:

- CVE-2026-53914 affects Kotlin before 2.4.20: code execution was possible through unsafe deserialization in build-cache metadata.
- After the Kotlin 2.4.20 security update, minified Android builds emitted `R8: An error occurred when parsing kotlin metadata`, showing that the R8 bundled with the existing AGP toolchain did not fully understand the new Kotlin metadata.
- Android's current Kotlin compatibility table requires R8 9.1.29 for Kotlin 2.4 class files and documents overriding AGP's bundled R8 when a newer compiler is required.
- The R8 project documents `https://storage.googleapis.com/r8-releases/raw` as its Maven repository for stable/dev prebuilts. R8 9.1.29 is resolved only from that buildscript-scoped repository; normal application dependencies remain restricted to Google Maven and Maven Central.

Remediation:

- Hermes Bridge uses Kotlin 2.4.20 for the Android, JVM, Compose and serialization plugins.
- The Android module was migrated from the removed string `kotlinOptions.jvmTarget` DSL to typed `compilerOptions` with JVM target 17.
- Gradle build cache is explicitly disabled with `org.gradle.caching=false` as additional defense in depth.
- `settings.gradle.kts` pins `com.android.tools:r8:9.1.29` on the Android build classpath instead of accepting an older bundled R8 for minification.
- The additional R8 repository is scoped to `pluginManagement.buildscript.repositories` and content-filtered to `com.android.tools:r8`; it cannot resolve application/runtime dependencies through `dependencyResolutionManagement`.
- CI captures the reproducible unsigned minified release log and fails if the Kotlin-metadata parsing warning returns.
- The Kotlin/R8 combination must pass protocol/relay/app unit tests, Hermes MCP tests, Android lint, debug APK, signed release verification, reproducible unsigned minified release APK and relay distribution builds before adoption.

References:

- https://nvd.nist.gov/vuln/detail/CVE-2026-53914
- https://www.jetbrains.com/privacy-security/issues-fixed/
- https://blog.jetbrains.com/kotlin/2026/09/kotlin-2-4-20-released/
- https://developer.android.com/build/kotlin-support
- https://r8.googlesource.com/r8/

## Runtime and application dependencies

### Ktor 3.1.2

Reviewed known Ktor client advisory CVE-2024-49580 / GHSA-8qv4-773j-c979. It affects `ktor-client-core-jvm` versions below 2.3.13; Hermes Bridge uses Ktor 3.1.2, so that advisory does not require a remediation bump.

The project does not upgrade Ktor solely because newer feature releases exist. A future update should be driven by a security fix, Android/network compatibility need, or a concrete product requirement and must run the full relay/Android test suite.

Reference:

- https://github.com/advisories/GHSA-8qv4-773j-c979

### Shizuku API/provider 13.1.5

The Maven API/provider artifact version is intentionally treated separately from the installed Shizuku Android application's release number. No raw shell is exposed to Hermes regardless of Shizuku version: privileged access remains behind typed Android tools and local exact-target approvals.

No actionable advisory requiring a library-version change was identified in this review. Future Shizuku changes require re-checking permission semantics and the typed privileged boundary before upgrading.

### kotlinx.coroutines / kotlinx.serialization / AndroidX

No actionable security advisory requiring an immediate version change was identified in this review for the versions currently used by Hermes Bridge. They remain under Dependabot monitoring. A newer version alone is not sufficient reason for a broad coordinated upgrade.

## Python MCP adapter

The adapter intentionally has a narrow direct dependency surface (`mcp>=2,<3`). Dependabot monitors `/hermes_mcp`. Major MCP SDK changes must be reviewed for tool registration, transport, schema and authorization behavior before adoption.

## GitHub Actions

Dependabot monitors GitHub Actions versions. Every external `uses:` reference in committed workflows is pinned to the full commit SHA resolved from the reviewed upstream release tag, with the corresponding version tag retained as a same-line comment so Dependabot can keep the pin and its version documentation current.

`ci/verify-actions-pinned.sh` fails CI if an external workflow action is changed back to a mutable tag/branch reference or if the verifier can no longer find any external action references. Local actions and `docker://` references are excluded from that rule because they do not use a GitHub repository ref.

Workflow permissions default to `contents: read`; the temporary write permission used once to generate and commit the standard Gradle Wrapper was removed immediately afterward.

References:

- https://docs.github.com/en/actions/reference/security/secure-use
- https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/secure-your-dependencies/auto-update-actions

## Ongoing policy

1. Prefer patched stable releases when an advisory actually affects the project.
2. Avoid unrelated bulk upgrades in the same change as a security fix.
3. Keep dependency repositories narrow and centralized.
4. Keep the Gradle Wrapper distribution checksum pinned.
5. Run unit tests, Hermes MCP tests, Android lint, APK build and relay distribution build after build-tool/dependency changes.
6. Re-audit the Hermes Android tool allowlist after any Shizuku, Android privilege or MCP SDK change.
7. Do not enable dependency-update auto-merge for security-sensitive tooling without a separate policy decision.
