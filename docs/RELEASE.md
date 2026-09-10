# Signed release process

Hermes Bridge keeps release signing keys outside the repository. Android requires installable/updateable APKs to be signed, and the same signing identity must be preserved for future updates.

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

## Environment

Export all four signing values. Partial configuration fails during Gradle configuration instead of silently producing an unsigned artifact.

```bash
export HERMES_BRIDGE_SIGNING_STORE_FILE=/secure/path/hermes-bridge-release.jks
export HERMES_BRIDGE_SIGNING_STORE_PASSWORD='...'
export HERMES_BRIDGE_SIGNING_KEY_ALIAS=hermes-bridge
export HERMES_BRIDGE_SIGNING_KEY_PASSWORD='...'
export HERMES_BRIDGE_APKSIGNER="$ANDROID_SDK_ROOT/build-tools/<exact-version>/apksigner"
```

Passwords are consumed from environment variables and are never stored in Gradle files or passed as command-line password arguments.

## Release gate

Run:

```bash
release/verify-reproducible-signed-apk.sh
```

The gate performs two clean signed release builds with the same source, toolchain, and keystore. Each build is verified with `apksigner`; the script then requires the two signed APKs to be byte-for-byte identical. The final artifact and SHA-256 checksum are:

```text
app/build/outputs/apk/release/app-release.apk
app/build/outputs/apk/release/app-release.apk.sha256
```

If the two APKs differ, do not publish the build. Resolve the nondeterministic input or toolchain difference first.

For every published release, record the Git commit, JDK version, Android SDK/Build Tools version, exact `apksigner` path/version, certificate SHA-256 fingerprint reported by `apksigner`, and APK SHA-256. Keep the production keystore backed up separately from those public release records.

## CI coverage

Pull-request CI creates an ephemeral test-only RSA keystore outside the checkout, exercises the same signing configuration, verifies both signed APKs with `apksigner`, and requires two clean signed builds to be byte-for-byte identical. CI does not contain or use the production signing key. It then creates the normal unsigned release APK separately for artifact inspection.
