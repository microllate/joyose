# Scene scheduling: isolate PowerKeeper Qualcomm boosts

## Goal

When Scene manages CPU/GPU scheduling, prevent `com.miui.powerkeeper` from issuing Qualcomm boost requests that can compete with Scene.

## Implementation

- The hook remains scoped to the `com.miui.powerkeeper` package.
- Intercepts `android.util.BoostFramework.perfLockAcquire(int, int[])` and returns handle `0` without acquiring a performance lock.
- Intercepts `android.util.BoostFramework.perfHint(int, String, int, int)` and returns status `0` without forwarding the hint.
- Keeps existing FPS policy hooks and diagnostic hooks.
- Does not hook Scene or globally disable BoostFramework, so other processes can continue to request boosts.

## Validation

Build with the repository GitHub Actions workflow (`gradle assembleDebug`). On-device, enable the module for PowerKeeper, reboot, and verify logs contain `blocked PowerKeeper perfLockAcquire` or `blocked PowerKeeper perfHint`. Then test Scene's CPU/GPU profiles and check for regressions in responsiveness, thermal behavior, and battery use.

## Scope and caution

This blocks Java BoostFramework requests made from PowerKeeper, not every possible vendor scheduling path. If PowerKeeper uses a native path that bypasses these APIs, that path will not be intercepted. The hook does not disable kernel thermal protection.
