# Qualcomm AP Engine GPU action mapping

## Current device evidence

Device: `23013PC75G` (Adreno 730 v3).

Runtime observation during 王者荣耀 at 90 FPS:

- `governor`: `msm-adreno-tz`
- `min_freq`: `220000000`
- `max_freq`: `900000000`
- `target_freq`: `900000000`
- sampled `gpu_load`: roughly 14–28%
- `gpuclk`: `900000000`
- `force_clk_on`: `0`
- `force_bus_on`: `0`
- `force_rail_on`: `0`
- `throttling`: `0`
- `thermal_pwrlevel`: `0`

`trans_stat` proves the KGSL governor is dynamically switching across the complete OPP table; 900 MHz is therefore not a permanent KGSL frequency lock.

## AP Engine process boundary

`libapengine.so` is **not** loaded into `com.miui.powerkeeper`.

Runtime process scan found:

```text
PID=1462
/vendor/bin/hw/vendor.qti.hardware.perf-hal-service
/vendor/lib64/libapengine.so
```

Therefore a normal LSPosed Java hook scoped to PowerKeeper cannot directly intercept `libapengine.so` native calls.

## Native call chain

The extracted AArch64 `libapengine.so` contains these relevant symbols:

```text
PredictiveEngine::selectGpuBoostVal(int)
PredictiveEngine::applyAction(int,int,int,int)
APActionManager::updateApplyActionQueue(int,ActionParams&,bool)
APActionManager::applyAction(int,ActionParams&)
```

`selectGpuBoostVal(int)` implements:

```text
input == 1:
    internal value at +0x290 == 500 -> return 0
    otherwise                         -> return 1

input == 2:
    internal value at +0x290 == 1000 -> return 2
    otherwise                         -> return 1

otherwise -> return 1
```

`APActionManager::applyAction()` obtains thermal mitigation state before applying an action. It then resolves the action entry and eventually calls:

```text
perf_lock_acq_rel(duration, resource_vector, ...)
```

This confirms that AP Engine is an adaptive performance layer rather than a direct KGSL frequency setter.

## QGPE action map

`QGPEActionMap.xml` contains:

```xml
<Group ActionCat="Gpu">
    <Opcode Resource="0x42804000" Name="GPU_PWR_LVL" Supported="Yes">
        <Level Val="4" Pos="0"/>
        <Level Val="2" Pos="1"/>
        <Level Val="0" Pos="2"/>
    </Opcode>
</Group>
```

The source evidence establishes the three GPU action positions and the Qualcomm resource ID, but **does not yet prove that Pos 0/1/2 correspond directly to 900/645/220 MHz**. That mapping must not be hard-coded without runtime confirmation.

## Implementation boundary

The current Joyose module must not disable the whole AP Engine, thermal mitigation, CPU actions, DDR actions, scheduler actions, or KGSL governor.

The intended optimization remains:

```text
com.tencent.tmgp.sgame
        -> 90 FPS
        -> reduce unnecessary GPU boost only
        -> preserve CPU / DDR / scheduler / thermal behavior
```

However, because the AP Engine lives inside the vendor Perf HAL process, a Java-only LSPosed hook cannot safely implement the native GPU action interception at this boundary. The next implementation step therefore requires identifying a PowerKeeper-side request that can be narrowed to the same GPU resource, or a separate native injection mechanism. No GPU action should be modified until the resource-to-OPP mapping is confirmed.

## Safety conclusion

Do not modify KonaBess voltage tables, KGSL `min_freq`/`max_freq`, or the governor as part of this experiment. Keep the current working configuration as the baseline.
