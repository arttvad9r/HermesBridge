# Local screen-capture privacy boundary

Hermes Bridge currently treats Android `MediaProjection` as a local, user-consented capability. It is not a remote screenshot tool and no screenshot pixels are exposed through the relay, MCP, Telegram, audit history, intents, logs, or app-private files.

## Current frame lifecycle

Each Android consent grant may back at most one non-secure `MediaProjection.createVirtualDisplay()` call. The capture surface is bounded to at most 1,600 pixels on either axis, 1,500,000 total pixels, and a 6,000,000-byte process-local RGBA copy.

Exactly one frame is acquired. The copied pixel buffer is redacted in place, overwritten immediately after local processing, and then the `Image`, `ImageReader`, `VirtualDisplay`, and `MediaProjection` resources are released. There is no repeated-frame stream and no retained screenshot artifact.

## Feasible redaction implemented today

Hermes Bridge masks locally derivable system-edge content before the temporary frame is erased:

- system bars;
- display cutout regions;
- the currently visible software keyboard (IME).

System/cutout insets are read from Android `WindowInsets`. The IME inset is visibility-sensitive and is sampled from a fresh `WindowInsets` snapshot after the frame arrives so a keyboard that appeared after capture setup is not missed.

The fresh inset snapshot must still describe the same maximum-window geometry used to configure the capture surface. If the source geometry changed before redaction, for example because of rotation, capture fails closed instead of applying a mask in the wrong coordinate space.

Independent edge masks are unioned by taking the maximum inset on each edge rather than summing overlapping regions. Positive source insets scale conservatively into the bounded capture surface so downscaling cannot leave a one-pixel system-UI seam.

## Sensitive app content

The standard build does not use `AccessibilityService` for autonomous Hermes UI control and does not run OCR or semantic password-field detection on screenshots. Therefore Hermes Bridge does not claim to identify or redact arbitrary sensitive fields inside application content.

This limitation is intentional. Adding Accessibility, OCR, semantic field detection, retained images, serialization, a remote screenshot trigger, or any pixel transport would create a new privacy/security boundary and requires separate review before implementation.

## Secure windows

The capture display is deliberately non-secure. Android remains responsible for blanking `FLAG_SECURE` and other protected-window content. Hermes Bridge must never create a secure virtual display or otherwise attempt to bypass platform secure-window protections.

## Remote boundary

The standard Hermes tool registry does not register screenshot or capture commands. MediaProjection consent can only be initiated locally on the phone. A future remote pixel consumer must not inherit permission merely because the local one-frame primitive exists; it requires a separate reviewed design, explicit session policy, bounded transport, and an updated threat model.
