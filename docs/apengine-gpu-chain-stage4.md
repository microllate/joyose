# AP Engine GPU chain — stage 4

## New static-analysis result

The AArch64 `libapengine.so` was disassembled directly. `PredictiveEngine::applyAction(int,int,int,int)` and `APActionManager::applyAction(int,ActionParams&)` provide more precise structure than the previous symbol-only analysis.

### `PredictiveEngine::applyAction`

At `0x4116c`, the function receives four integer arguments. It copies:

- arg1 (`w1`) into `w20` as a flag/control field;
- arg2 (`w2`) into `w23` and later uses it as an index into a table whose entries are 0x28 (40) bytes apart;
- arg3 (`w3`) into `w22` on one path;
- arg4 (`w4`) into `w21`.

The function repeatedly reads the object field at `this + 0x290`. The same value is compared against:

- `0x1f4` = 500
- `0x3e8` = 1000

This is consistent with the previously recovered `selectGpuBoostVal()` logic.

A key path creates an `ActionParams`-like object and stores a selected value into its first 32-bit field. In the GPU-related branch the selection is effectively:

```text
if currentValue == 500:
    selected = 0
elif currentValue == 1000:
    selected = 2
else:
    selected = 1
```

The same three-way selection appears inline in `applyAction`, not only in the standalone `selectGpuBoostVal()` function. This strengthens the conclusion that `0/1/2` are **abstract action positions**, not direct MHz values.

## `APActionManager::applyAction`

At `0x3da18`, the native function receives an action index (`w1`) and an `ActionParams` reference (`x2`). Important observations:

1. It checks `ThermalListener::getThermalMitigation()` before applying the action.
2. It validates the action index against an internal table.
3. Each table entry has a 0x28-byte stride.
4. The entry contains an action resource vector pointer at offset `+0x0` and a duration/value field at `+0x20`.
5. The final native call is:

```text
perf_lock_acq_rel(duration, resource_vector, ...)
```

Specifically, one path loads:

```text
w0 = [tableEntry + 0x20]
x2 = [tableEntry + 0x0]
w4 = 0
bl  perf_lock_acq_rel
```

Therefore the GPU action value selected by `PredictiveEngine` is used to select/build an action entry before the Qualcomm perf-lock resource vector is submitted.

## What this proves

The chain is now more precise:

```text
FPS / GPU state
    -> PredictiveEngine decision
    -> abstract GPU action value 0/1/2
    -> action table lookup
    -> resource vector + duration
    -> perf_lock_acq_rel()
    -> Qualcomm Perf resource handling
```

The QGPE XML still defines the GPU resource as:

```text
Resource 0x42804000 = GPU_PWR_LVL
Pos 0 -> Val 4
Pos 1 -> Val 2
Pos 2 -> Val 0
```

However, the disassembly does **not** expose a direct `0/1/2 -> MHz` mapping. The selected value is an abstract action-table selector. The actual resource vector is obtained from the action table.

## Consequence for the optimization

Do not replace `4/2/0` blindly. The safe target is the action-table decision layer:

```text
90 FPS + large GPU headroom
    -> choose a lower GPU action position
    -> let the existing perf HAL resolve the resource vector
    -> keep thermal gating intact
```

This is materially safer than writing KGSL `min_freq/max_freq`, replacing the governor, or modifying KonaBess.

## Next required proof

The remaining missing link is the contents of the AP Engine GPU action table loaded from `QGPEActionMap.xml` and, specifically, which resource vector corresponds to action positions 0/1/2 at runtime. Static disassembly alone establishes the selector and perf-lock submission but not the final KGSL OPP.

The next experiment should therefore observe the AP Engine's actual action selection and the KGSL `cur_freq/target_freq` at the same time, or obtain the action table/resource-vector representation from the vendor perf configuration. No performance modification should be enabled until that mapping is verified.
