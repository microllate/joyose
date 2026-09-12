# GPU Adaptive Safe Monitor — Stage 1

This is a KernelSU module for **read-only** Adreno/KGSL observation.

It deliberately does not replace the governor or compete with PowerKeeper, Joyose, the CPU scheduler, DDR control, thermal control, or perf-lock policy.

## What it records

Every second:

- foreground package
- `/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq`
- GPU busy percentage when exposed by the driver
- GPU devfreq governor
- a battery/skin-oriented thermal zone when available
- timestamp

Log file:

`/data/local/tmp/joyose-gpu-adaptive/monitor.log`

## Safety boundary

Stage 1 performs no writes under `/sys`. It is intended to collect a baseline while playing 王者荣耀 at 90 FPS before any control logic is introduced.

## Installation

Install the generated `joyose-gpu-adaptive-safe-v0.1.0-stage1.zip` from the GitHub Actions artifact as a KernelSU module.

After reboot, verify:

```sh
su
head -20 /data/local/tmp/joyose-gpu-adaptive/monitor.log
```

Uninstall/disable the module from KernelSU when the measurement session is finished.
