# AP Engine GPU Decision — Stage 7

## Concrete progress

Static disassembly of `libapengine.so` now identifies the real call chain into `PredictiveEngine::applyAction(int,int,int,int)`.

`PredictiveEngine::predictionThread()` calls `announceAction(...)` at `0x4038c`; `announceAction()` forwards four integer arguments directly into `applyAction()` at `0x41048`:

```text
predictionThread
  -> announceAction(w1, w2, w3, w4)
  -> applyAction(arg1, arg2, arg3, arg4)
```

At `0x40370-0x40388`, the arguments are assembled as:

- arg1 (`w1`) = `(w23 & 0x26) | w24`
- arg2 (`w2`) = `w28`
- arg3 (`w3`) = integer conversion of `s1`
- arg4 (`w4`) = `w25`

The `w28` state is selected immediately before this call. It is **not simply GPU utilization**. One branch compares an internal value at `this+0x1e4` against `25.0`; another branch compares the same value against the float bit pattern `0x42960000` (75.0). Therefore the observed 25/75 thresholds belong to an internal prediction/state decision and must not yet be labelled as raw GPU-utilization thresholds.

## GPU action selection inside applyAction

Inside `PredictiveEngine::applyAction()` the GPU action index is selected around `0x412c0-0x41304`.

The internal field `this+0x290` is compared against two boost-scale values:

```text
this+0x290 == 500  AND condition == 1 -> GPU action 0
this+0x290 == 1000 AND condition == 2 AND arg3 >= 15 -> GPU action 2
otherwise -> GPU action 1
```

This is important: the GPU action index is derived from **multiple inputs**, not from one direct frequency value.

## Action table / perf-lock path

The selected action index is stored into an action record. `APActionManager::applyAction()` uses a 40-byte-per-action record. The record contains:

- offset `0x00`: resource vector pointer
- offset `0x18`: vector/resource count or related bookkeeping
- offset `0x20`: perf-lock handle/duration field
- offset `0x24`: enabled/valid flag

For the normal path, `APActionManager::applyAction()` eventually executes:

```text
resource_vector = actionRecord[index].vector
perf_lock_acq_rel(..., resource_vector, ...)
```

The QGPE map identifies the GPU resource as `0x42804000` (`GPU_PWR_LVL`) with action levels `4`, `2`, and `0`.

## What this proves

The user's desired strategy is feasible in architecture, but the correct interception point is now clearer:

```text
AP Engine telemetry/prediction
        ↓
 predictionThread state
        ↓
 announceAction
        ↓
 applyAction
        ↓
 GPU action index 0/1/2
        ↓
 APActionManager action table
        ↓
 GPU_PWR_LVL resource
        ↓
 Qualcomm perf HAL / KGSL
```

We should **not** treat the 25/75 values as GPU-utilization thresholds yet. The next task is to identify what `this+0x1e4`, `w25`, and the `arg3 >= 15` condition represent, then correlate those values with `getGpuUsage()` / `getGpuHeadRoom()` and runtime GPU frequency.

## Proposed final controller

Once those meanings are confirmed, implement a conservative hysteretic controller at the AP Engine GPU-action decision boundary:

```text
initial/high demand -> allow normal boost
low sustained demand -> one GPU action lower
high sustained demand -> restore one action
```

Do not modify CPU, DDR, scheduler, thermal mitigation, KGSL governor, or KonaBess voltage tables during this stage.
