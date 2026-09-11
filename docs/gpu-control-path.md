# PowerKeeper / Joyose GPU control path findings

## Scope

This investigation targets only:

- `com.xiaomi.joyose`
- `com.miui.powerkeeper`

`android` / `system_server` is intentionally out of scope.

## Current conclusion

The uploaded `GPU功耗固定303` Magisk module is only a KGSL policy switch:

```sh
echo "3" > /sys/class/kgsl/kgsl-3d0/default_pwrlevel
echo "0" > /sys/class/kgsl/kgsl-3d0/max_pwrlevel
```

It does **not** contain the GPU frequency/voltage table. The actual OPP/voltage mapping is supplied by the separate GPU undervolting setup (for example, KonaBess). Therefore this module is a useful control/reference, but it should not be installed as part of the tracing experiment yet.

JADX inspection of the current PowerKeeper build found no direct Java writes to `default_pwrlevel`, `max_pwrlevel`, or `min_pwrlevel` in the examined `perfengine` / `statemachine` paths. PowerKeeper does read KGSL/devfreq telemetry, but that alone does not prove GPU control.

## Confirmed Qualcomm performance path

`com.miui.powerkeeper.perfengine.g` is the Qualcomm `QcomBoost` wrapper. Its `e(int duration, int[] resources)` reaches Android's `BoostFramework.perfLockAcquire`, while `d(int hint, int duration, int tpid)` uses `perfHint`.

The existing hook must preserve this distinction:

- `QcomBoost.e(...)`: performance resource lock; trace only for now.
- `QcomBoost.d(4227, 400, -1)`: gesture/animation boost. Do **not** block or rewrite it.

## Important universal foreground path

Runtime reflection on the actual device shows:

```text
PeGameController.a[class miui.process.ForegroundInfo] -> void
```

This is significant because the parameter is the framework/MIUI `miui.process.ForegroundInfo`, not `com.miui.powerkeeper.statemachine.ForegroundInfo`.

The previous `CpuDdrHandler` diagnostic used the wrong class name and therefore failed with `ClassNotFoundException`. The tracing module now uses `miui.process.ForegroundInfo`.

The foreground path is important for system-wide tracing because PowerKeeper receives foreground component changes such as Launcher, Settings, Security Center, and MT Manager, not just games.

## Scheduler/perf command path

`PeGameController.S(ArrayList)` / `X(ArrayList)` feed `PeSchedHandler`.

`PeSchedHandler.l(ArrayList<String>)` writes commands to:

```text
/data/system/whetstone/perf_data
```

and then calls:

```text
PerfUtils.c("/data/system/whetstone/perf_data")
```

`PerfUtils.c(...)` sets:

```text
mcd.extra.params = "sudebug sched " + path
ctl.start = "mcd_init"
```

This is currently one of the most important paths to trace because it can carry scheduler/performance commands without requiring direct Java writes to KGSL sysfs.

## SchedConfig finding

PowerKeeper's `SchedConfig` contains parsing fields for:

- `gpu_level_high`
- `gpu_level_medium`
- `gpu_level_low`
- `gpu_level`

However, on the tested device the runtime configuration lists for these GPU levels were empty. `PeSchedController` is also largely a no-op at runtime. This makes the generic `SchedConfig` route unlikely to be the primary active GPU control mechanism on this device.

## Current diagnostic changes

`PerfPathTraceHook` was added and registered in `xposed_init`. It traces, without modifying behavior:

- `QcomBoost.e/f`
- `CpuDdrHandler.systemNocDDRLLCTuning(miui.process.ForegroundInfo)`
- `NoiseCpuHandler` perf/boost methods
- `PeGameController.a(miui.process.ForegroundInfo)`
- `PeSchedHandler.l(...)`

Caller stacks are logged for these paths so the next runtime capture can distinguish:

- foreground/app switching
- game performance
- scheduler/perf command dispatch
- CPU/DDR tuning
- Qualcomm performance locks

## Do not implement yet

Do not globally lower GPU performance or rewrite arbitrary Qualcomm `int[]` perf-lock resources until a real GPU resource/control request is observed. A generic resource rewrite risks changing CPU, DDR, scheduler, thermal, or touch/gesture behavior and could conflict with the user's KonaBess OPP/voltage configuration.

The next evidence target is a real `QcomBoost.e` / `BoostFramework.perfLockAcquire` request or a concrete `PeSchedHandler.l` command that can be correlated with GPU frequency changes during ordinary daily use.
