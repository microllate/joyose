# LSPosed GPU Daily Baseline Experiment

## Purpose

Use a normal daily app workload as a baseline before testing `com.tencent.tmgp.sgame`.
The first target is the ChatGPT Android app. This experiment is observation-only: it must not change GPU, CPU, governor, thermal, perf-lock, or PowerKeeper policy values.

## Scope

LSPosed scopes:

- `com.xiaomi.joyose`
- `com.miui.powerkeeper`

Do not use `android/system_server`.

## Test workload

Keep the phone in normal use for 3-5 minutes while ChatGPT is in the foreground:

1. Scroll a long conversation repeatedly.
2. Open the keyboard and type for a while.
3. Scroll up/down through the conversation.
4. Open/close images if present.
5. Continue normal UI interaction.

Record a timestamped log during the whole session.

## Measurements

The diagnostic module should capture, when available:

- foreground package/process
- KGSL GPU current frequency
- KGSL GPU utilization/load
- Qualcomm `GPU_PWR_LVL` resource `0x42804000`
- PowerKeeper Qualcomm perf-lock/perf-hint activity
- battery/thermal temperature

The important observation is the relationship between UI activity and GPU frequency transitions, not a single peak value.

## Interpretation goals

Establish a normal-use baseline:

- whether GPU normally stays near low OPPs
- whether UI interaction causes short high-frequency bursts
- whether PowerKeeper participates in those bursts
- whether `GPU_PWR_LVL` is visible through the Java-side hooks
- whether temperature changes correlate with sustained GPU activity

A ChatGPT result must not be interpreted as a game result. The app is only a convenient repeatable workload for establishing the device's ordinary graphics behavior.

## Safety boundary

No control logic is part of this experiment. Do not write `/sys`, do not alter KGSL/devfreq settings, do not modify Qualcomm perf-lock values, and do not disable vendor scheduling.

## Next step after baseline

Compare the daily-use trace against the existing `com.tencent.tmgp.sgame` trace. Only after the native AP Engine/QGPE-to-KGSL boundary is understood should any GPU ceiling/control experiment be considered.
