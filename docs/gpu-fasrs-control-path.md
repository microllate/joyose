# GPU-FASRS control-path probe

## Why the controller was stopped

The previous GPU-FASRS controller used `devfreq/max_freq` as a GPU ceiling. During a 王者荣耀 test it reported `cap=765000000` while `cur_freq` reached `900000000`. Therefore `max_freq` cannot currently be treated as the final effective GPU ceiling on this device.

`cur_freq` is an instantaneous operating point, not an average game frequency. A short 900 MHz sample does not mean the GPU stayed at 900 MHz, but a value above the configured ceiling is enough to reject that node as the only control path.

The previous frame feedback path also returned `fps=NA`, so the adaptive loop did not have a trustworthy frame signal.

## Probe policy

This revision is **observation-only**. It performs no GPU frequency, CPU, governor, thermal, or perf-lock writes.

For `com.tencent.tmgp.sgame` it records:

- `devfreq/max_freq`, `min_freq`, `cur_freq`, `available_frequencies`
- common KGSL/devfreq clock nodes when present (`clock_mhz`, `max_clock_mhz`, `min_clock_mhz`, `freq_table_mhz`)
- power-level/policy nodes when exposed (`max_pwrlevel`, `min_pwrlevel`, `thermal_pwrlevel`, `pwrscale`)
- `gpu_busy_percentage`
- battery/skin temperature when available
- `dumpsys gpu --gpuwork` per-UID information when the platform exposes it

The probe explicitly reports whether `max_freq` is writable but does not write it.

## Decision gate for the real controller

A real GPU-FASRS controller should only be enabled after the probe identifies an effective control interface that is actually reflected by the hardware operating point. The final controller should retain the system governor and make only a narrow ceiling/offset adjustment with hysteresis, then restore the original state when the game exits.

No KonaBess GPU OPP/voltage table is modified by this work.
