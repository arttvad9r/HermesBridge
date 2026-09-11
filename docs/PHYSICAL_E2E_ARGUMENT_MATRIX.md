# Physical mutation approval argument matrix

Use this alongside `PHYSICAL_E2E.md` section 5. The changed-argument check is about the mutation's normalized security semantics, not transport-only metadata.

For every row, first obtain and approve the original local approval, then send the changed request **before** consuming the original approval. The changed request must not execute the mutation. Finally retry the original normalized action and verify that its approval is still the only approval that can execute it exactly once.

| Tool | Original approved semantics | Safe changed-argument probe | Expected result |
| --- | --- | --- | --- |
| `files.delete` | disposable target path + current target metadata | another disposable existing target, or change the approved target metadata before retry | old approval is not consumed; changed target requires a distinct approval |
| `apps.forceStop` | fixture package name | another syntactically valid non-Hermes package name | old approval is not consumed; no force-stop occurs without a new approval |
| `apps.revokePermission` | fixture package + `android.permission.CAMERA` + resolved Android user | another permission/package value that fails validation or would require a different normalized action | original approval remains unconsumed and no changed revoke occurs |
| `apps.uninstall` | fixture package + `keepData` | toggle `keepData` | old approval is not consumed; changed uninstall requires a distinct approval |
| `apps.install` | verified APK SHA-256, size, package/version/signers + `replace` | toggle `replace`, or use different verified APK content | old approval is not consumed; changed install requires a distinct approval |

For `apps.install`, changing only `artifactId`, `downloadToken`, or `fileName` for the **same verified APK content and same `replace` value is intentionally not a changed approval action**. Those fields are transport metadata; the local approval is deliberately bound to the verified package identity/content instead. Raw command replay protection remains stricter and still binds the transport request separately.

A changed probe may be rejected by validation before an approval prompt (for example a permission not requested by the fixture). That is acceptable only if it demonstrably performs no mutation and leaves the original approved action consumable; the purpose of this check is to prove that an approval cannot authorize a different semantic mutation.
