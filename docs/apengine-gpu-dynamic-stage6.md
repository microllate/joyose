# AP Engine GPU Dynamic Control — Stage 6

## Key correction: `selectGpuBoostVal()` is not the active call site

A direct scan of the AArch64 disassembly shows no direct `BL` call to the exported `PredictiveEngine::selectGpuBoostVal(int)` at `0x423ac` from `PredictiveEngine::applyAction()`.

Instead, the same decision logic is inlined inside `PredictiveEngine::applyAction()`.

Relevant block:

```text
0x412c0  load internal state at object + 0x290
0x412c4  compare state == 1
0x412cc  compare +0x290 == 500
0x412d4  -> GPU action value 0

0x412dc  compare an action/context value against 15
0x412ec  compare internal state == 2
0x412f4  compare +0x290 == 1000
0x412fc  -> GPU action value 2

0x41304  otherwise -> GPU action value 1
```

Therefore the active path is:

```text
PredictiveEngine::applyAction()
        |
        +-- inline GPU action selection
        |      0 / 1 / 2
        |
        +-- build action parameter/vector
        |
        +-- APActionManager::updateApplyActionQueue()
        |
        +-- APActionManager::applyAction()
        |
        +-- perf_lock_acq_rel()
```

## Important implication

The `0/1/2` decision is **not itself a GPU MHz selection**. It is an action selection value which is later resolved through the AP Engine action map.

The two explicit special cases are:

- internal value `500` can select GPU action `0`;
- internal value `1000` can select GPU action `2` when the relevant context value is at least `15`;
- otherwise GPU action `1` is selected.

The exact meaning of the object field at `+0x290` is still not established as MHz. It is an AP Engine internal boost/state scale.

## New research direction

The correct next target is now the caller/context that supplies the arguments to `PredictiveEngine::applyAction(int,int,int,int)` and the object fields used above.

In particular, identify:

1. what argument `w1` represents (the bitmask checked at `0x411a4`);
2. what argument `w2/w3/w4` represent;
3. what the object fields at `+0x34`, `+0x38`, `+0x3c`, `+0x40`, `+0x44` represent;
4. what the state at `+0x290` represents;
5. how the selected action value is associated with the GPU `ActionCat` entry and `GPU_PWR_LVL` resource.

## Relation to the desired controller

This is actually favorable to the desired global dynamic controller. The existing AP Engine already has GPU telemetry and a decision stage. Rather than writing a competing frequency governor, the eventual hook should preferably alter only the GPU action decision while preserving CPU/DDR/scheduler/thermal handling.

The desired policy remains:

```text
initial boost allowed
       -> observe GPU demand/headroom
       -> sustained low demand: reduce GPU performance level
       -> sustained high demand (~80% utilization): restore/increase level
       -> repeat with hysteresis
```

No runtime GPU frequency or voltage modification is made by this research commit.
