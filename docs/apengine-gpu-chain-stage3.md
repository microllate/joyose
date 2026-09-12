# AP Engine GPU action chain — Stage 3

## New binary evidence

Analyzed the extracted AArch64 `libapengine.so` from device `23013PC75G` (Adreno 730 v3).

The dynamic symbol table exposes:

```text
PredictiveEngine::applyAction(int,int,int,int)        @ 0x4116c
PredictiveEngine::selectGpuBoostVal(int)              @ 0x423ac
APActionManager::updateApplyActionQueue(int,ActionParams&,bool) @ 0x3d7c8
APActionManager::applyAction(int,ActionParams&)       @ 0x3da18
```

## `selectGpuBoostVal()` is confirmed

Disassembly at `0x423ac`:

```text
input == 1:
    *(this + 0x290) == 500  -> return 0
    otherwise                -> return 1

input == 2:
    *(this + 0x290) == 1000 -> return 2
    otherwise                -> return 1

otherwise -> return 1
```

The `+0x290` value is therefore an internal AP Engine parameter used to select one of three GPU action positions. This is stronger evidence than a string-only analysis, but it still does not identify the physical KGSL OPP for each position.

## `PredictiveEngine::applyAction()` queueing

The function constructs `ActionParams` entries and eventually calls:

```text
APActionManager::updateApplyActionQueue(actionId, actionParams, true)
```

The relevant call is around `0x41914`–`0x41928`.

This means the predictive layer does not directly write KGSL sysfs. It creates an action request and sends it to the action manager.

## `APActionManager::applyAction()` final perf call

The native implementation performs thermal gating before applying an action:

```text
ThermalListener::getInstance()
        ↓
getThermalMitigation()
        ↓
possible early return / skip
```

After resolving the action configuration, the function calls:

```text
perf_lock_acq_rel(duration, resourceVector, ...)
```

At `0x3dbc4` and `0x3dc80` the arguments are loaded from the resolved action entry before the call.

Therefore the confirmed architecture is:

```text
PredictiveEngine
    ↓
GPU action position (0/1/2)
    ↓
APActionManager actionMap
    ↓
ActionParams/resource vector
    ↓
perf_lock_acq_rel()
    ↓
Qualcomm Perf HAL resources
```

## Important implication for the current problem

The earlier PowerKeeper-side diagnostic did not observe `0x42804000` in Java `BoostFramework.perfLockAcquire` calls during the 王者荣耀 90-FPS test. It did observe PowerKeeper issuing Qualcomm hint `4227`.

Together with the process scan showing `libapengine.so` loaded by:

```text
/vendor/bin/hw/vendor.qti.hardware.perf-hal-service
```

this makes the following path the current leading explanation:

```text
王者荣耀
   ↓
PowerKeeper
   ↓
Qualcomm hint 4227
   ↓
Perf HAL / AP Engine
   ↓
PredictiveEngine
   ↓
GPU action selection
   ↓
APActionManager
   ↓
GPU_PWR_LVL resource
```

This is an inference from the combined evidence, not yet a complete runtime trace from hint 4227 to GPU resource.

## What remains to prove

1. Identify how hint `4227` reaches the AP Engine predictive path.
2. Identify which `applyAction()` input corresponds to GPU selection.
3. Resolve the action ID/key to the `Gpu` / `GPU_PWR_LVL` entry from `QGPEActionMap.xml`.
4. Determine the actual resource vector emitted for positions 0/1/2.
5. Correlate those positions with the device's KGSL OPP table.

Only after these are proven should the Joyose module modify a GPU action.

## Optimization target

The desired behavior remains:

```text
王者荣耀 90 FPS
    ↓
retain adaptive GPU scaling
    ↓
remove unnecessary high GPU boost/headroom
    ↓
lower GPU power/voltage where safe
    ↓
maintain 90 FPS
```

Do not disable thermal mitigation, CPU/DDR/scheduler actions, KGSL governor, or modify the working KonaBess configuration during this stage.
