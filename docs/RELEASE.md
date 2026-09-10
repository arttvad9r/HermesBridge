# Signed release process

Hermes Bridge keeps release signing keys outside the repository. Android requires installable/updateable APKs to be signed, and the same signing identity must be preserved for future updates.

This release process separates two properties that must not be conflated:

1. **build reproducibility** — two clean unsigned minified APK builds from the same source/toolchain must be byte-for-byte identical;
2. **release authenticity** — the production APK is signed once with the external release key and the resulting signature is verified with the Android SDK `apksigner`.

Hermes Bridge does not claim that repeated Gradle signing invocations produce byte-identical signed APKs. The signed artifact's recorded SHA-256 is the release artifact identity.

## Prerequisites

Use a clean checkout of the exact commit being released, the repository's pinned Gradle Wrapper, JDK 17, the Android SDK required by the project, Bash, and a specific `apksigner` executable from the Android SDK Build Tools.

Create and back up the production signing keystore outside the repository. The Android command-line documentation uses an RSA release key, for example:

```bash
keytool -genkeypair -v \
  -keystore /secure/path/hermes-bridge-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias hermes-bridge
```

Use strong, separate passwords. Do not commit the keystore or passwords. If Hermes Bridge is distributed outside Play App Signing, losing the app-signing key means existing installations cannot receive normally signed updates.

## 1. Verify the reproducible build

Start with all `HERMES_BRIDGE_SIGNING_*` variables unset and run:

```bash
release/verify-reproducible-unsigned-apk.sh
```

The gate performs two clean minified release builds and requires the two unsigned APKs to be byte-for-byte identical. It leaves the second verified artifact and its SHA-256 at:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
app/build/outputs/apk/release/app-release-unsigned.apk.sha256
```

If the APKs differ, do not publish a release. Treat the build as non-reproducible and identify the nondeterministic input or toolchain behavior first.

## 2. Configure the external signing identity

Export all four signing values. The keystore path must be absolute and outside the repository checkout. Partial configuration fails during Gradle configuration instead of silently producing an unsigned artifact.

```bash
export HERMES_BRIDGE_SIGNING_STORE_FILE=/secure/path/hermes-bridge-release.jks
export HERMES_BRIDGE_SIGNING_STORE_PASSWORD='...'
export HERMES_BRIDGE_SIGNING_KEY_ALIAS=hermes-bridge
export HERMES_BRIDGE_SIGNING_KEY_PASSWORD='...'
export HERMES_BRIDGE_APKSIGNER="$ANDROID_SDK_ROOT/build-tools/<exact-version>/apksigner"
```

Passwords are consumed from environment variables and are never stored in Gradle files or passed as command-line password arguments.

## 3. Build and verify the signed artifact

Run:

```bash
release/build-signed-apk.sh
```

The script refuses a dirty worktree, performs a clean minified release build using the external signing identity, verifies the produced APK with `apksigner verify --Werr --print-certs`, and writes its SHA-256. The final files are:

```text
app/build/outputs/apk/release/app-release.apk
app/build/outputs/apk/release/app-release.apk.sha256
```

For every published release, record the Git commit, JDK version, Android SDK/Build Tools version, exact `apksigner` path/version, certificate SHA-256 fingerprint reported by `apksigner`, and signed APK SHA-256. Keep the production keystore backed up separately from those public release records.

## CI coverage

Pull-request CI verifies both boundaries without production secrets:

- a deliberately partial signing configuration must fail closed during Gradle configuration;
- an ephemeral test-only RSA keystore is created outside the checkout and the signed Gradle release path must pass `apksigner` verification;
- after signing variables leave scope, two clean unsigned minified release builds must be byte-for-byte identical;
- the reproducible unsigned APK and SHA-256 are uploaded for inspection.

CI never contains or uses the production signing key.
