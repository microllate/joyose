# AP Engine GPU decision — Stage 8

## Concrete finding

`PredictiveEngine::applyAction(int,int,int,int)` at `0x4116c` does not derive the GPU action directly from a raw GPU-utilization threshold at the point where the action is emitted.

The GPU action is selected from an internal boost-state value at `PredictiveEngine + 0x290` plus the incoming action context.

Relevant machine code:

```text
0x412a4: load AP-engine subobject
0x412b4: load state byte/word from subobject + 0xec
0x412c0: load PredictiveEngine +0x290
0x412c8: compare subobject state == 1
0x412cc: compare +0x290 == 500
0x412d4: GPU action = 0
0x412dc: compare incoming w3 >= 15
0x412e4: load +0x290
0x412ec: compare subobject state == 2
0x412f4: compare +0x290 == 1000
0x412fc: GPU action = 2
0x41304: otherwise GPU action = 1
```

Equivalent logic for this path:

```text
if (internalState == 1 && boostState == 500)
    gpuAction = 0;
else if (incomingValue >= 15 && internalState == 2 && boostState == 1000)
    gpuAction = 2;
else
    gpuAction = 1;
```

There is a second, simpler GPU-selection path later in the same function for another action flag. It uses the same `+0x290` boost state and maps:

```text
boostState == 500  -> GPU action 0
boostState == 1000 -> GPU action 2
otherwise           -> GPU action 1
```

## Meaning of +0x290

`PredictiveEngine` constructor initializes `+0x290` to `1`.

`PredictiveEngine::startThread()` later loads an indexed value from the container beginning at `+0x2a8` and stores that value atomically into `+0x290`.

The same `+0x290` value is consumed by `selectCpuBoostVal()`, `selectGpuBoostVal()`, and `selectDdrBoostVal()`. Therefore it is an internal **boost-state/control level**, not GPU utilization and not a MHz value.

The constants `500` and `1000` are control-state values. They must not be interpreted as GPU frequencies.

## Important correction to the dynamic-control hypothesis

The desired user strategy is still technically viable, but the correct interception point is now clearer:

```text
GPU telemetry/history
    -> AP Engine prediction/state
    -> internal boost state (+0x290)
    -> GPU action 0/1/2
    -> QGPE GPU_PWR_LVL
    -> Qualcomm perf HAL
    -> KGSL
```

A direct hook that treats `+0x290` as utilization would be incorrect.

The next required reverse-engineering target is the writer of the indexed container at `+0x2a8` / the source of the index read from the AP-engine object at `+0x35c`. That is the most direct way to connect the predictive telemetry to the boost state.

## Safety boundary

Do not alter CPU, DDR, scheduler, thermal mitigation, KGSL governor, or KonaBess voltage tables during this stage. No final GPU action remap is justified until the action-to-OPP mapping and boost-state writer are both proven.
