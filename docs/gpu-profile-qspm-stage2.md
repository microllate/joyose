# GPU Profile / QSPM Stage 2 Analysis

## Device runtime profile

Runtime file:
`/data/vendor/gaming/com.tencent.tmgp.sgame-gpu`

Observed size: 332 bytes.

The runtime profile is **not byte-identical** to the JADX asset `8750/profiles/com.tencent.tmgp.sgame-gpu` (asset size 283 bytes). Earlier notes incorrectly described them as identical; this is corrected here.

## Protobuf envelope

The runtime file parses as nested protobuf `Any`-style data:

- outer field 1 contains an inner message
- inner field 1 contains a `type.googleapis.com/GraphicsProfilePrivate` Any
- Any field 2 contains a 244-byte opaque private payload
- inner field 2 contains a 33-byte key/value entry:
  `DisablePrivateProfileData = TRUE`

The private payload begins with:

`c9 0c 49 09 42 95 c4 4a a6 00 ...`

The APK 8750 profile begins with the same general private-profile structure but has a different payload and size. The payload is therefore device/profile-version specific.

## QSPM interpretation

The payload does not expose plain-text GPU frequency, voltage, KGSL OPP, `max_freq`, or `min_freq` values. Static inspection of the QSPM HIDL implementation also shows profile/file handling APIs such as `getGpuProf()` and `setAppProfile()`, but no direct evidence that this layer writes GPU OPP voltage or frequency.

The current evidence supports this chain:

`Joyose/PowerKeeper -> ProfileManager -> libprofilemanager-jni -> libupdateprof.qti.so -> QSPM HAL -> /data/vendor/gaming/*.gpu -> Qualcomm graphics/profile consumer`

The QSPM HAL should therefore not be modified as a GPU-voltage hook without further evidence.

## Qualcomm Adreno / AP Engine findings

The newly extracted `/vendor/lib64/libapengine.so` is an AArch64 Qualcomm Adaptive/Predictive Performance Engine component. Its exported symbols include:

- `APActionManager::applyAction(int, ActionParams&)`
- `PredictiveEngine::applyAction(int,int,int,int)`
- `PredictiveEngine::selectGpuBoostVal(int)`
- `APDataManager::getGpuHeadRoom(...)`

Relevant strings include `QGPEActionMap.xml`, `GPU_HEADROOM_MAX`, `getGpuHeadRoom`, `collectGPUStats`, `collectFPS`, `DetectGameFPS`, `Applying Boost boostEntity %d`, and `HEADROOM_REGULATOR(%d idx): Lock Req on %d with %d`.

Disassembly confirms `APActionManager::applyAction()` ultimately calls the imported `perf_lock_acq_rel()` with parameters obtained from its action map. Therefore the AP Engine is a real Qualcomm performance-request path, not merely a statistics component.

The exact `selectGpuBoostVal()` logic is:

- input level `1`: if the internal value at object offset `0x290` equals `500`, return `0`; otherwise return `1`.
- input level `2`: if that internal value equals `1000`, return `2`; otherwise return `1`.
- other inputs return `1`.

The values `500` and `1000` are internal AP Engine values; they must **not** be interpreted as MHz without further evidence.

## QGPEActionMap.xml: confirmed GPU action

The device file `/vendor/etc/lm/QGPEActionMap.xml` was extracted and uploaded. Its `Gpu` group contains exactly one GPU opcode:

```xml
<Opcode Resource="0x42804000" Name="GPU_PWR_LVL" Supported="Yes">
    <Level Val="4" Pos="0"/>
    <Level Val="2" Pos="1"/>
    <Level Val="0" Pos="2"/>
</Opcode>
```

Thus Qualcomm's Gaming Performance Engine has an explicit GPU performance action named `GPU_PWR_LVL`, resource `0x42804000`, with three mapped positions: `Pos 0 -> 4`, `Pos 1 -> 2`, and `Pos 2 -> 0`.

This is strong evidence that the AP Engine can issue a GPU performance-level request through Qualcomm PerfLock. It does **not yet prove** that these values directly equal KGSL `qcom,gpu-pwrlevels` indices; that mapping still requires runtime/PerfLock evidence.

## Current optimization conclusion

Do not disable the AP Engine and do not change the GPU OPP table yet. The AP Engine also contains CPU/DDR actions and thermal checks, and `applyAction()` can be skipped when thermal mitigation is active. The safest research direction is to identify the conditions that select GPU positions 0/1/2 and measure whether a lower GPU action still preserves the game's target FPS.

The target remains: reduce sustained gaming power/temperature (goal approximately -3 C) while preserving actual game performance and leaving CPU scheduling and thermal protection intact.

## Next research target

Map:

`FPS + GPU headroom -> PredictiveEngine GPU boost selection -> GPU_PWR_LVL position -> PerfLock resource/value`

Then validate the selected position during `com.tencent.tmgp.sgame` runtime before changing any Joyose hook.

No runtime profile modification has been performed.
