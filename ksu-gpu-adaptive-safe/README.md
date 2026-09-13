# GPU Adaptive Safe Monitor — Stage 1

This is a KernelSU module for **read-only** Adreno/KGSL observation.

It deliberately does not replace the governor or compete with PowerKeeper, Joyose, the CPU scheduler, DDR control, thermal control, or perf-lock policy.

## What it records

The GPU monitor samples every 200 ms:

- foreground package
- `/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq`
- GPU busy percentage when exposed by the driver
- GPU devfreq governor
- a battery/skin-oriented thermal zone when available
- timestamp with milliseconds

`monitor.log` is automatically capped at **300 lines**.

Log file:

`/data/local/tmp/joyose-gpu-adaptive/monitor.log`

## Game frame timing

Android SurfaceFlinger `timestats` provides game-layer average FPS and present-to-present timing histograms. This is useful for games because it measures the display-side frame timing rather than relying only on the application's FPS counter.

A manual helper is included:

```sh
su
/data/adb/modules/joyose-gpu-adaptive-safe/capture-game-timestats.sh
```

The helper:

1. enables SurfaceFlinger timestats;
2. waits for `com.tencent.tmgp.sgame` to enter the foreground;
3. records until the game leaves the foreground;
4. extracts the game's `averageFPS`, `totalFrames`, and `presentToPresent histogram`;
5. disables timestats automatically;
6. keeps `frame_stats.log` capped at 300 lines.

Output:

`/data/local/tmp/joyose-gpu-adaptive/frame_stats.log`

This helper is **not** started automatically by `service.sh`, so normal module operation remains read-only GPU observation without enabling a graphics trace continuously.

## Safety boundary

Stage 1 performs no writes under `/sys`. It is intended to collect a baseline while playing 王者荣耀 at 90 FPS before any control logic is introduced.

## Installation

Install the generated KSU ZIP from the GitHub Actions artifact as a KernelSU module.

After reboot, verify:

```sh
su
head -20 /data/local/tmp/joyose-gpu-adaptive/monitor.log
```
