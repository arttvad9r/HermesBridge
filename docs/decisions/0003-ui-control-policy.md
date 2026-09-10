# ADR 0003: Keep autonomous UI control out of AccessibilityService

Status: Accepted

## Context

Hermes Bridge is an AI-agent companion, not an accessibility tool whose core purpose is assisting people with disabilities.

Android documents `AccessibilityService` as a service for assisting users with disabilities. Current Google Play policy also prohibits non-accessibility-tool apps from using the Accessibility API to autonomously initiate, plan and execute actions or decisions. Hermes-driven screen navigation would fall directly into that risk area.

The current product and architecture drafts predate that constraint and name Accessibility as the default optional UI-control backend. Implementing that plan would couple the standard app to a policy-sensitive broad UI authority before a narrower backend has been exhausted.

MediaProjection has a different boundary: on current target SDKs the user must consent to each capture session, a projection foreground service is required, one `MediaProjection` instance cannot be reused for independent capture sessions, and projection cannot be silently restored from `BOOT_COMPLETED` on Android 15+.

## Decision

The standard Hermes Bridge build will not ship an `AccessibilityService` for autonomous Hermes UI automation.

UI-control remains a future optional capability, but its architecture is constrained as follows:

1. Prefer a typed Android API whenever the requested action has one.
2. For advanced device actions, evaluate narrow typed Shizuku operations. Each operation must have a fixed purpose and validated parameters; no agent-controlled executable, shell string, argv array, environment, working directory or arbitrary filesystem path may cross the privileged boundary.
3. If screen pixels are required, use an explicit short-lived MediaProjection session initiated by the user. Ask for system capture consent for every new session, run only inside the required `mediaProjection` foreground service, stop when projection is revoked/stopped, and never restore capture at boot.
4. Treat `FLAG_SECURE`/otherwise unavailable content as inaccessible. Do not add a bypass path.
5. Keep UI-control disabled by default, visibly active, locally auditable and separately revocable from the base Hermes connection and Shizuku setup.
6. Require local approval or an explicitly entered UI-control session before typed tap/swipe/input actions can execute.

If a future product variant has a genuine accessibility-tool purpose or a deterministic user-authored automation use case that could lawfully use AccessibilityService, it requires a separate ADR, manifest/release-channel review and explicit user disclosure. It must not silently broaden the standard build.

## Release-channel consequence

The current release process supports signed APK distribution outside Play, but that is not a reason to make AccessibilityService the default control plane. The standard manifest remains free of an autonomous Accessibility service so the same core build does not depend on a distribution-policy exception.

A future non-Play experimental UI-control flavor may be considered only after its typed Shizuku/MediaProjection surface, consent UX, sensitive-data handling and physical-device tests are reviewed separately.

## Consequences

- The planned P1 "Accessibility setup" placeholder is removed from the standard setup roadmap.
- P2 UI automation starts with a narrow backend/session design, not an Accessibility service.
- Screen capture cannot be a hidden always-on feed; user consent is part of each MediaProjection session lifecycle.
- UI actions will take more engineering than a generic Accessibility or shell bridge, but the authority granted to Hermes remains explicit and reviewable.
- Existing base, SAF, Usage Access and Shizuku capabilities are unaffected.

## Primary references

- Android `AccessibilityService`: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Google Play AccessibilityService API policy: https://support.google.com/googleplay/android-developer/answer/10964491
- Android 14 MediaProjection behavior: https://developer.android.com/about/versions/14/behavior-changes-14#media-projection
- Android foreground-service type for MediaProjection: https://developer.android.com/develop/background-work/services/fgs/service-types#media-projection
