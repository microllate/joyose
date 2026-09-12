# AP Engine GPU action-map stage 4

Device target: Xiaomi 23013PC75G / Adreno 730v3.

## Confirmed static chain

`libapengine.so` contains `APActionManager::initParseXML()`, which parses `QGPEActionMap.xml` with TinyXML2.

For each `<Opcode>` it reads `Resource`, and for each `<Level>` it reads `Val` and `Pos`. `ConvertToIntArray()` converts the comma-separated resource string to an integer vector. The parsed structure stores two integer vectors per opcode: the resource vector and the level-value vector.

For the GPU opcode:

```xml
<Opcode Resource="0x42804000" Name="GPU_PWR_LVL" Supported="Yes">
    <Level Val="4"  Pos="0"/>
    <Level Val="2"  Pos="1"/>
    <Level Val="0"  Pos="2"/>
</Opcode>
```

Static code in `APActionManager::registerActions(...)` confirms action states are assembled from the parsed opcode resource vector and the value vector indexed by the selected position. Each `ActionStateHolder` is 0x28 bytes apart; the action table uses a 0x28-byte stride.

`APActionManager::applyAction()` obtains the selected action state, reads the action state's resource vector pointer and duration/handle field, and calls `perf_lock_acq_rel(...)`.

Therefore `0/1/2` in `PredictiveEngine::selectGpuBoostVal()` are action positions, not MHz values. For `GPU_PWR_LVL`, those positions select Qualcomm resource values `4/2/0`.

## PredictiveEngine mapping

`PredictiveEngine::selectGpuBoostVal(int)`:

- input 1 + internal state at +0x290 == 500 -> action position 0
- input 2 + internal state at +0x290 == 1000 -> action position 2
- otherwise -> action position 1

The same selection logic is inlined in `PredictiveEngine::applyAction()`.

`PredictiveEngine::applyAction()` builds `ActionParams` and eventually calls:

```text
APActionManager::updateApplyActionQueue(action_id, ActionParams&, true)
```

The action ID is separate from the resource vector; the GPU resource value is resolved later from the action table.

## What is still NOT proven

We must not yet claim that GPU_PWR_LVL values 4/2/0 equal specific KGSL frequencies such as 900/645/220 MHz. The XML defines Qualcomm performance-resource levels, but this `libapengine` binary does not contain a direct MHz table for this resource.

Remaining mapping:

```text
GPU_PWR_LVL 4/2/0
    -> Qualcomm perf HAL interpretation
    -> KGSL GPU power-level / OPP
    -> actual device frequency
```

## Next diagnostic target

Trace the actual resource vector passed to `perf_lock_acq_rel` from the AP Engine path and correlate it with `/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq` at the same time. This is the safest way to establish the final mapping before any modification.

Do not disable thermal mitigation, CPU/DDR/scheduler actions, KGSL governor, AP Engine, or KonaBess.
