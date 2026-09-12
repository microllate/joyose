# AP Engine GPU Dynamic Control — Stage 5

## 结论

本阶段确认 `libapengine.so` 已经具备实现“先正常升高、低负载降档、高负载再升档”所需的核心遥测链路。下一步应继续追踪 headroom 到 `selectGpuBoostVal()` 的决策输入，而不是固定限制 GPU 最高频率。

## 已确认的 GPU telemetry

`APDataManager::collectGPUStats(void*)` @ `0x1fc2c`：

```text
APDataManager
  -> mutex lock
  -> vector @ object + 0x5b0
  -> APMetaMeter::getGpuUsage(vector<vector<float>>&)
```

因此 AP Engine 内部确实持续保存 GPU usage history。

`GpuMeter` 同时导出：

```text
GpuMeter::getAvlGpuFreqs(vector<int>&)
GpuMeter::getGpuUsage(vector<vector<float>>&)
```

说明 AP Engine 同时具备可用 GPU 频率集合和 GPU 使用率数据。

## Headroom

`APDataManager::getGpuHeadRoom(void*, int, int, int)` @ `0x1fd60` 对 GPU 历史数据进行计算，并产生内部 headroom/派生指标。代码中明确出现 `500`、`1000` 等 AP Engine 内部尺度值，以及浮点历史数据累积、比较和归一化处理。

这不是简单的固定频率开关，而是基于历史 GPU 数据计算出的动态指标。

目前不能把 `500/1000` 解释为 MHz。

## 与动态调度目标的关系

目标控制逻辑应保持：

```text
初始高负载
    -> 允许原生 boost
    -> GPU usage/headroom 稳定后判断是否过度性能
    -> 降低一个 GPU action/level
    -> 持续观察
    -> 如果 GPU usage/headroom 显示接近瓶颈
    -> 恢复更高 GPU action/level
```

应使用滞回和持续时间，避免频繁升降档。

## 下一步静态追踪

重点追：

```text
APMetaMeter::getGpuUsage
        |
        v
APDataManager::getGpuHeadRoom
        |
        v
PredictiveEngine::applyAction
        |
        v
PredictiveEngine::selectGpuBoostVal
        |
        v
GPU action index
        |
        v
QGPEActionMap GPU_PWR_LVL
        |
        v
perf_lock_acq_rel
```

特别需要确认 `PredictiveEngine::applyAction(int,int,int,int)` 四个参数分别代表什么，以及 headroom 结果在哪些条件下决定 GPU action index。

## 不做的事情

暂不固定 cap 900 MHz -> 645/815/765；暂不修改 KGSL governor；暂不修改 CPU/DDR/scheduler/thermal；暂不修改 KonaBess 电压表。

最终目标是让 GPU 尽可能以完成当前 workload 所需的最低性能档运行，而不是永久锁死某一个频率。
