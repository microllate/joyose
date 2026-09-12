# GPU Adaptive Safe — Stage 1

## Goal

Build a read-only GPU observation layer for Qualcomm Adreno 730 on device 23013PC75G before making any frequency-control changes.

The objective is to determine whether the GPU is spending meaningful time at unnecessarily high OPPs while 王者荣耀 is running at 90 FPS, without competing with Xiaomi PowerKeeper, AP Engine, KGSL governor, thermal control, CPU scheduling, or DDR scheduling.

## Safety boundary

Stage 1 MUST NOT:

- write `/sys/class/kgsl/kgsl-3d0/devfreq/*`
- change `scaling_governor`
- change CPU frequency or scheduler settings
- change DDR/bus settings
- change thermal settings
- submit Qualcomm perf locks
- override PowerKeeper/AP Engine decisions
- modify KonaBess configuration

It is observation only.

## Data to collect

At a low sampling rate (about 1 Hz):

- package/foreground state
- GPU `cur_freq`
- GPU available frequencies
- GPU load when exposed by KGSL
- frame/FPS information when safely available
- battery/skin temperature when safely readable
- governor name

For each sample, derive:

- current OPP index
- estimated GPU headroom
- whether the frame target is being maintained

## Decision states

The monitor reports only three states:

- `SAFE_DOWN`: frame target is stable and GPU appears substantially under-utilized
- `HOLD`: insufficient evidence to change anything
- `NEED_UP`: frame timing/load indicates that additional GPU performance may be required

These states are advisory only in Stage 1.

## Hysteresis

Never react to one sample. A future control stage should require multiple consecutive samples before any limit changes and should immediately release the limit when frame timing deteriorates.

The initial 王者荣耀 target is 90 FPS. The project is not attempting to force 120 FPS.

## Intended control architecture

The eventual design is a ceiling layer rather than a competing governor:

`PowerKeeper/AP Engine -> KGSL governor -> GPU`

with the experimental layer acting only as a temporary maximum-frequency constraint when there is strong evidence of surplus GPU capacity. The system remains responsible for selecting the actual OPP below that ceiling.

## Why this is preferable

The reverse-engineering work established that PowerKeeper/AP Engine can select Qualcomm GPU actions through the native performance stack, while direct Java-side perflock tracing did not observe the GPU resource during the 王者荣耀 test. Continuing to reverse every native decision branch is therefore not required for the first practical experiment.

The first experiment should establish a measurable correlation between GPU frequency, GPU load, frame stability, and temperature. Only after that evidence exists should any control path be enabled.

## Current device baseline

Device: Xiaomi 23013PC75G / Adreno 730v3.

Observed GPU frequency table includes:

`900, 862, 815, 765, 710, 645, 580, 515, 439, 364, 324, 285, 220 MHz`

The current KonaBess modification lowers the voltage corner associated with 285 MHz and is known to boot and run games normally. It must remain unchanged during this experiment.
