# Joyose 工作基准点

## 第一次成功解除王者 60 FPS 限制

保存基准：`48ac92181ef5e7e7fffee7c26c123976fa25451f`

提交：`Hook PowerKeeper FPS policy`

时间：2026-09-11 01:19:51 +08:00

### 关键实现

Hook：`com.miui.powerkeeper.statemachine.DisplayFrameSetting`

处理两个 `setFpsAync` 重载，以及最终的 `setScreenEffect`。

当 PowerKeeper 下发 `60 FPS` 或更低的正 FPS 时，将参数改为 `120 FPS`。

### 为什么保存这个版本

这是目前项目中已经实际验证成功的“王者 60 → 120”工作版本，应作为后续 GPU/PerfLock 实验的稳定回退基准。

后续诊断和 GPU 性能实验不要覆盖或破坏这个基准。若新实验出现异常，可回退到该 commit。

### 后续修复版本

紧接着的 `d326a9e6145a6ca12a4b51d7a522534c64613f15`（`Fix PowerKeeper FPS hook varargs`）只是修复 Hook 参数传递方式，功能目标相同。

因此：

- 原始首次成功基准：`48ac92181ef5e7e7fffee7c26c123976fa25451f`
- 稳定修复版：`d326a9e6145a6ca12a4b51d7a522534c64613f15`

当前 GPU 研究应继续在独立诊断分支进行，不修改这两个基准提交。
