# GPU-FASRS

GPU-FASRS is the GPU-side counterpart to the fas-rs idea used for CPU scheduling.

## Control loop

```text
王者荣耀 foreground
        ↓
SurfaceFlinger completed-frame timestamps
        ↓
actual presentation FPS + maximum frame gap
        +
KGSL gpu_busy_percentage + current GPU frequency
        ↓
3 consecutive safe samples
        ↓
move GPU devfreq max_freq down by one OPP

FPS drop / large frame gap / high GPU busy
        ↓
raise max_freq by one OPP immediately
```

The kernel GPU governor remains enabled. GPU-FASRS only changes the `devfreq/max_freq` ceiling when the foreground package is `com.tencent.tmgp.sgame`.

## Safety rules

- Only `com.tencent.tmgp.sgame` is controlled.
- `min_freq` is never changed.
- The GPU governor is never changed.
- No CPU frequency, CPU governor, thermal, perf-lock, or PowerKeeper settings are changed.
- The controller lowers the ceiling only after 3 consecutive samples at approximately 90 FPS, GPU busy <= 72%, and no frame gap over 22 ms.
- It raises the ceiling immediately when FPS < 89 FPS, GPU busy >= 85%, or a frame gap exceeds 33 ms.
- The ceiling moves one OPP at a time, so a sudden workload spike can recover without jumping straight to a fixed frequency.
- When 王者荣耀 leaves foreground, the session ceiling is restored.

## Data sources

The controller reads the Qualcomm KGSL `cur_freq`, `gpu_busy_percentage`, and devfreq frequency table. KGSL exposes GPU busy percentage as a read-only statistic, while standard Qualcomm device configurations also expose GPU devfreq min/max frequency controls. AOSP documents `/sys/class/kgsl/kgsl-3d0/devfreq/max_freq` as a GPU maximum-frequency control on Qualcomm devices. citeturn4search0turn0search0

For frame pacing, the controller uses `dumpsys SurfaceFlinger --latency SurfaceView` without issuing `--latency-clear`. AOSP continues to expose the latency dumper, and its frame data contains completed presentation timestamps suitable for deriving presentation rate and frame gaps. citeturn1search3turn1search13

Android also defines `dumpsys gpu --gpuwork` for per-UID GPU work information. This stage does not depend on that command because HyperOS/vendor builds may omit or restrict the tracepoint; it can be added as a later validation signal. citeturn2search0turn2search11

## Current target

The first target is 王者荣耀 at 90 FPS. The objective is to find the lowest dynamic GPU ceiling that keeps frame pacing stable, rather than locking the GPU to a fixed frequency.

This stage is intentionally conservative. It is not yet tuned for a 39 °C target; temperature is logged for correlation while frame stability has priority.
