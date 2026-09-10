# Joyose

面向小米 HyperOS / MIUI 的 LSPosed 模块，用于解除 Joyose 云控性能限制，并绕过 PowerKeeper 注入的 60 FPS / 刷新率限制。

## 当前功能

- `com.xiaomi.joyose`
  - 拦截 Joyose Gson 云控配置解析
  - 将 `common_config` / `booster_config` 性能云控参数关闭
  - 拦截 `MiuiSettings.SettingsCloudData#getCloudDataList`
- `android` / `system_server`
  - Hook `DisplayModeDirector#getDesiredDisplayModeSpecs`
  - 检测 MIUI/HyperOS 产生的 60 FPS render-rate 上限
  - 在最终显示规格提交前将受限的最大刷新率恢复到 120 Hz

## 使用

1. 下载 GitHub Actions 生成的 APK。
2. 安装后在 LSPosed 中启用模块。
3. 勾选两个作用域：
   - `com.xiaomi.joyose`
   - `android`（部分 LSPosed 版本可能显示为 system_server）
4. 首次启用或更新模块后执行：

```sh
su
pm clear com.xiaomi.joyose
am force-stop com.xiaomi.joyose
```

5. 重启一次手机，让 framework hook 生效。
6. 进入游戏测试 FPS。

## 原理

MIUI/HyperOS 的 PowerKeeper 可以向系统显示策略注入 60 FPS 的刷新率 Vote。即使屏幕本身仍支持 120 Hz，`dumpsys display` 也会出现类似：

```text
PRIORITY_MIUI_REFRESH_RATE -> RenderVote{ RefreshRateVote{ mMinRefreshRate=0.0, mMaxRefreshRate=60.0 } }
```

本模块除了处理 Joyose 云控，还在 `DisplayModeDirector` 最终计算结果处解除这个 60 Hz 上限，因此不依赖 PowerKeeper 的具体混淆类名。

> 注意：该功能针对支持高刷新率的设备。模块不会修改系统 APK，也不会卸载或禁用 PowerKeeper。

## 编译

GitHub Actions 使用 JDK 17 + Gradle 8.9 构建 Debug APK。
