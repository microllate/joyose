# Joyose

面向小米 HyperOS / MIUI 的 LSPosed 模块，用于拦截 `com.xiaomi.joyose` 的云控性能配置。

## 当前功能

- 仅作用于 `com.xiaomi.joyose`
- 拦截 Joyose Gson 云控配置解析
- 将 `common_config` / `booster_config` 的性能云控参数置空并关闭
- 拦截 `MiuiSettings.SettingsCloudData#getCloudDataList`
- 默认目标：解除 Joyose 对游戏动态 FPS / 性能策略的云控限制

## 编译

GitHub Actions 会自动使用 JDK 17 + Gradle 8.7 编译 Debug APK。

## 使用

1. 下载 Actions 生成的 APK
2. 安装并在 LSPosed 中启用
3. 作用域只选择 `com.xiaomi.joyose`
4. 强制停止 Joyose 后重新进入游戏测试

> 不修改 Joyose APK，也不需要替换系统文件。

## 说明

Joyose 的具体类名和云控 JSON 结构会随 MIUI / HyperOS 版本变化，因此后续会根据实际设备日志继续增加兼容 Hook。
