# AP Engine GPU probe

## Purpose

The fas-rs-inspired monitor already measures frame cadence and per-thread CPU workload in a target app. GPU needs a different observation path because `/proc/<pid>/task/<tid>/stat` exposes CPU time, not GPU execution time.

This diagnostic stage therefore probes the PowerKeeper process for Java bridge classes/methods corresponding to the AP Engine symbols previously identified during native analysis:

- `GpuMeter.getGpuUsage()`
- `GpuMeter.getAvlGpuFreqs()`
- `APMetaMeter.getGpuUsage()`
- `APDataManager.getGpuHeadRoom()`
- `APDataManager.collectGPUStats()`
- `AdaptiveEngine.*`
- `PredictiveEngine.*`

The hook scans PowerKeeper's APK dex entries for matching class names and hooks only the GPU-stat method names when a Java bridge exists.

## Safety

This stage is observation-only. It does not:

- modify method arguments or return values;
- write KGSL/sysfs nodes;
- change GPU/CPU governors;
- acquire performance locks;
- alter thermal policy;
- replace PowerKeeper scheduling.

## Expected result

On the next PowerKeeper process start, look for:

```text
[Joyose-APGPU] SCAN ... candidates=...
[Joyose-APGPU] HOOK ...getGpuUsage...
[Joyose-APGPU] HOOK ...getGpuHeadRoom...
[Joyose-APGPU] ...getGpuUsage result=...
```

If no matching Java bridge is present, the log will say `SCAN no matching AP Engine Java bridge classes`. That is still useful: it means the next GPU-observation layer must move below the Java wrapper (for example, native/AP Engine instrumentation), rather than repeatedly attempting privileged Java sysfs reads.

## fas-rs adaptation

fas-rs collects actual frame time and feeds a control policy only after a sufficient frame-time buffer is available. Its controller also adapts the effective target based on measured utilization. The Joyose project is deliberately keeping this phase read-only so the device-specific GPU signal can be validated before any control path is introduced.
