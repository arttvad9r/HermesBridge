# Disposable physical E2E fixture

`e2e-fixture` is a deliberately minimal Android app used only as a safe target for TypedDroid physical privileged-operation tests.

## Safety properties

- package: `io.github.arttvad9r.typeddroid.fixture`;
- no `INTERNET` permission;
- no storage, contacts, location, account or notification permissions;
- one runtime permission: `android.permission.CAMERA`;
- the camera hardware feature is optional and the app never opens or records from the camera;
- no application dependencies beyond the Android platform API;
- no user data is stored.

## Build

From the repository root:

```bash
./gradlew --no-daemon :e2e-fixture:lintDebug :e2e-fixture:assembleDebug
```

The APK is written to:

```text
e2e-fixture/build/outputs/apk/debug/e2e-fixture-debug.apk
```

For `install_apk`, upload that exact APK to the relay's dedicated APK staging directory and use the relay-provided artifact descriptor/hash rather than inventing transport metadata.

## Physical privileged-operation sequence

1. Install the fixture through the ordinary local Android path first and launch **TypedDroid E2E Fixture**.
2. Tap **Grant CAMERA permission for revoke test** and confirm the fixture shows `CAMERA permission: GRANTED`.
3. Use the connected agent's `force_stop_app` against `io.github.arttvad9r.typeddroid.fixture`; after approval, the foreground fixture Activity should close.
4. Launch it again, grant CAMERA if necessary, then use `revoke_app_permission` for package `io.github.arttvad9r.typeddroid.fixture` and permission `android.permission.CAMERA`. The agent does not supply an Android user ID; TypedDroid derives the current user locally. After approval, reopen/return to the fixture and confirm it shows `NOT GRANTED`.
5. Use `uninstall_app` against the fixture package and confirm it disappears only after local approval.
6. Stage the CI/local fixture APK and use `install_apk`; confirm the verified package is installed only after local approval.
7. Repeat the deny, changed-argument and single-use approval checks from `PHYSICAL_E2E.md` for these operations.

The fixture is disposable. Never substitute TypedDroid itself, a banking/authenticator app, launcher, messaging app, or another important package when validating privileged mutations.
