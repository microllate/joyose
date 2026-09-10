# PowerKeeper GPU path trace

## Current status

The module already confirms that `com.miui.powerkeeper` loads and that the FPS policy hooks work. Qualcomm `QcomBoost.e/d`, framework `BoostFramework.perfLockAcquire`, `PeGameController.p/q`, `DynamicTurboPowerHandler.systemTuning()`, and `SocOptimizationHandlerVersion2.perfLockAcquire()` are now instrumented.

## Why the next trace targets SchedConfig/PeSchedController

The decompiled PowerKeeper contains `SchedConfig` fields/configuration for `gpu_level_high`, `gpu_level_medium`, and `gpu_level_low`, while the visible Java perf-lock paths did not yet show a direct KGSL write. The new diagnostic build hooks `SchedConfig` constructors and GPU/scheduler/config-related methods, and traces `PeSchedController` methods, without changing their arguments or return values.

This is diagnostic only. No GPU voltage/frequency/performance value is modified by this trace.

## Additional routes closed by diagnostics

The build also traces `CpuDdrHandler.systemNocDDRLLCTuning()` and XR-specific `perfengine.i.e()` so that Java-side perf-lock routes are not mistaken for a missing Qualcomm GPU path.

## Important known exclusion

`QcomBoost.d(4227, 400, -1)` is a gesture-animation boost and must not be blocked or rewritten as a GPU control.

## Next evidence required from device log

After installing the new debug APK and restarting `com.miui.powerkeeper`, reproduce the workload that changes GPU behavior. Capture lines beginning with:

- `[Joyose-PowerKeeper] SchedConfig`
- `[Joyose-PowerKeeper] PeSchedController`
- `[Joyose-PowerKeeper] QcomBoost.e`
- `[Joyose-PowerKeeper] BoostFramework.perfLockAcquire`
- `[Joyose-PowerKeeper] SocOptimizationV2.perfLockAcquire`
- `[Joyose-PowerKeeper] CpuDdrHandler`
- `[Joyose-PowerKeeper] XRBoost`

The resource arrays and caller stacks are the key evidence for identifying the actual GPU performance control path before implementing any one-level-lower GPU policy.
