# Joyose / PowerKeeper 负优化审计

> 基线：`release/fps-no-douyin-120`
>
> 本分支只用于分析，不修改稳定版行为。

## 目标

确认 Joyose / PowerKeeper 是否存在“负优化”：在没有明确性能收益的情况下，对前台应用、GPU、CPU、DDR/Bus、Scheduler 或后台行为施加过度限制。

这里不把正常的温控、瞬时 Boost、后台省电直接定义为负优化；必须结合触发条件、持续时间、作用对象和运行时结果判断。

## 当前已确认的路径

### 1. FPS policy — 高优先级

`com.miui.powerkeeper.statemachine.DisplayFrameSetting`

重点方法：

- `setFpsAync(...)`
- `setScreenEffect(...)`

当前稳定版只对普通应用把 `<=60 FPS` 请求改为 `120 FPS`；`com.ss.android.ugc.aweme`（抖音）保持原值。

这部分已经有实际运行验证，因此暂时不再扩大 Hook 范围。

### 2. Qualcomm PerfLock — 高优先级审计

PowerKeeper 中的 `g.java` 使用 `android.util.BoostFramework`，涉及：

- `perfLockAcquire`
- `perfLockRelease`
- `perfHint`
- `perfLockReleaseHandler`

重点资源：

- `0x42804000` → `MPCTLV3_GPU_MIN_POWER_LEVEL`
- `0x42808000` → `MPCTLV3_GPU_MAX_POWER_LEVEL`
- `0x408...` → CPU frequency 相关资源
- `0x418...` → CPU/DDR bandwidth 相关资源

必须确认每个 request 的 acquire/release、持续时间和前台场景，不能看到资源 ID 就直接 Hook。

### 3. GPU minimum power level — 高优先级

已在 PowerKeeper profile 中发现：

`0x42804000, 0`

这证明 PowerKeeper 具备 GPU minimum power-level 请求能力，但尚未证明该请求会在当前设备上持续造成 GPU 性能限制。

下一步应通过运行时日志记录实际调用，而不是修改它。

### 4. QcomBoost 4227 — 暂不视为负优化

已经观察到：

`perfHint(4227, "PowerKeeper_QcomBoost", 400, -1)`

目前判断它属于短时手势/动画 Boost。不要为了省电直接禁用，否则可能降低 UI 跟手度。

### 5. DDR / Bus / CPU Boost — 中优先级

重点分析：

- `CpuDdrHandler`
- `DynamicTurboPowerHandler`
- `PeGameController`

判断标准不是“频率高”，而是：

1. 是否只在需要时触发；
2. 是否及时 release；
3. 是否作用于普通应用；
4. 是否存在过长的 timeout；
5. 是否在应用已经空闲后仍保持性能请求。

### 6. Whetstone / mcd — 中优先级

`PeSchedHandler` 会写：

`/data/system/whetstone/perf_data`

并通过 `mcd.extra.params` / `ctl.start=mcd_init` 触发更底层处理。

这一条需要继续追踪实际 command 内容和执行结果，暂不修改。

### 7. PerfFlinger — 中优先级

`SocOptimizationHandlerVersion2` 存在通过 `PerfFlinger` 反射调用性能控制的路径。

需要确认它与 BoostFramework/PerfLock 是否重复施加同类请求。

## 审计分类

每个发现最终归入以下类别：

- `GOOD_BOOST`：有明确交互/性能收益的瞬时 Boost
- `POWER_PROTECTION`：正常温控或功耗保护
- `NORMAL_POLICY`：合理的动态性能策略
- `SUSPECTED_NEGATIVE`：疑似过度限制或无效性能请求
- `NEEDS_RUNTIME_PROOF`：源码无法判断，必须抓运行时调用

## 下一步执行顺序

1. 不改 `release/fps-no-douyin-120`。
2. 在本分支增加诊断 Hook，只记录 PowerKeeper 的性能请求，不改变参数。
3. 分别观察：桌面滑动、设置、普通应用启动、视频、游戏、锁屏/解锁。
4. 记录 Qualcomm resource ID、参数、调用次数、持续时间、前台包名。
5. 找出“普通应用 + 持续限制”以及“重复/未释放请求”。
6. 只有出现明确负优化证据后，才单独建立修改分支。

## 当前原则

**先观测，再修改。**

稳定版保持不动；GPU KonaBess 电压 Corner 修改也保持独立。任何后续性能策略修改都从本审计分支另开实验分支。