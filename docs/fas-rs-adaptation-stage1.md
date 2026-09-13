# fas-rs adaptation — Stage 1

## Purpose

This document records the design adopted from `shadow3aaa/fas-rs` for the Joyose project. The goal is to measure application-side workload/frame behavior first, without competing with Xiaomi's scheduler and without writing CPU/GPU/thermal controls.

## What fas-rs does that matters here

fas-rs maintains a foreground-app PID and refreshes it from Window Manager data. Its `ProcessMonitor` measures per-thread CPU time from `/proc/<pid>/task/<tid>/stat`, periodically keeps the busiest threads, and reports the maximum observed thread utilization. The implementation updates at roughly 300 ms and refreshes the thread set at roughly 1 s.

The important architectural idea is therefore not a fixed GPU-frequency lock. It is a feedback loop driven by the workload actually produced by the foreground application.

## Why the current Joyose sysfs sampler is being removed

`com.miui.powerkeeper` runs as UID 1000. The Java-side `FileReader` sampler could not read the KGSL nodes on this device, so it only produced `?` for frequency/utilization/governor/min/max. Keeping that sampler enabled adds log traffic without providing a usable signal.

## Stage 1 design for Joyose

1. Keep the existing PowerKeeper/Joyose hooks observation-only.
2. Stop trying to read KGSL sysfs from the PowerKeeper Java process.
3. Measure application-side frame cadence in a test workload using `Choreographer`.
4. Measure application process/thread CPU activity through `/proc`, following the fas-rs approach.
5. Do not write GPU frequency, CPU frequency, governor, thermal limits, or perf-lock values.
6. Use the resulting measurements to determine whether a later control loop can safely reduce excess performance headroom while preserving the 90 FPS target.

## Important limitation

`Choreographer.FrameCallback` measures callback/vsync cadence; it is not equivalent to SurfaceFlinger present timestamps or the native `libgui` frame timing path used by some fas-rs revisions. Therefore Stage 1 is a workload/feedback prototype, not proof of actual display-present FPS.

## Device-specific baseline

- Device: Xiaomi 23013PC75G
- GPU: Adreno 730 v3
- Target game: 王者荣耀
- Current target: 90 FPS
- Current observed temperature: about 42 C
- Desired reduction: about 3 C, without meaningful performance loss
- KonaBess GPU OPP changes remain untouched in this stage.

## Source

Repository studied: `https://github.com/shadow3aaa/fas-rs`

Relevant implementation files include `src/cpu_common/process_monitor.rs` and `src/framework/scheduler/topapp.rs`.
