# PowerKeeper performance analysis

## Device-specific finding

The JADX package contains a device profile for `23013PC75G`, which is the device family relevant to this build. Its PowerKeeper game booster configuration uses Qualcomm-style `perflock` commands and direct `/sys` scheduler/CPU tuning paths.

`MtkBoost` is guarded by `FeatureParser.getBoolean("is_mediatek", false)`, so it is not the correct performance-control path for a Qualcomm device. The Qualcomm path is `PeGameController -> g (QcomBoost) -> android.util.BoostFramework.perfLockAcquire()`.

## Confirmed game-performance controls

The device cloud profile contains game-specific controls such as:

- `sys/module/perfmgr/parameters/perfmgr_enable`
- `sys/module/perfmgr/parameters/load_scaling_y`
- `sys/module/perfmgr/parameters/min_freq_limit_level`
- `sys/module/perfmgr/parameters/boost_minfreq`
- `sys/devices/system/cpu/cpu4/cpufreq/walt/target_loads`
- `sys/devices/system/cpu/cpu7/cpufreq/walt/target_loads`
- `sys/devices/system/cpu/cpu7/core_ctl/enable`
- `dev/cpuset/foreground/cpus`
- `dev/cpuset/top-app/cpus`
- `sys/devices/system/cpu/cpu0/cpufreq/walt/rtg_boost_freq`
- `proc/sys/walt/sched_asymcap_booster`
- Qualcomm `perflock` resource pairs such as `40C20100`, `40C20200`, `40C1C100`, and `40C1C200`.

These are not safe to classify as GPU-only from the Java layer alone. A single perflock request can combine CPU, scheduler, memory and GPU-related vendor resources.

## Current implementation strategy

The module already bypasses the PowerKeeper 60 FPS display policy at `DisplayFrameSetting.setScreenEffect(String,int,int)`.

The new diagnostic hook records the raw Qualcomm perflock command passed through `PeGameController.q(String)` without changing it. This is intentional: first capture the exact commands generated on the target device, then map the vendor resource IDs before selectively suppressing only GPU-related policy.

## Why we should not disable all PowerKeeper boosting

KonaBass can own the GPU frequency/voltage table at the kernel DVFS layer. PowerKeeper, however, can simultaneously control CPU scheduling, CPU cluster availability, cpusets and vendor performance locks. Disabling all PowerKeeper game boosting would unnecessarily remove useful CPU/scheduler behavior and could reduce frame stability.

The target design is therefore:

```text
KonaBass -> GPU frequency/voltage table
PowerKeeper -> CPU/scheduler/game policy
Joyose module -> remove only conflicting GPU performance commands
Thermal protection -> keep enabled
FPS policy -> 60 -> 120 bypass
```

## Next runtime diagnostic

After installing the diagnostic build, launch a game and collect:

```sh
logcat -d | grep -E "Joyose-PowerKeeper.*perflock"
```

The resulting commands will be mapped against the game-specific cloud profile. Only after that mapping should a command be filtered or modified.
