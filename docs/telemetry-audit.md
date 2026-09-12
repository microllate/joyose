# Joyose / PowerKeeper 网络与遥测审计

> 分支：`audit/negative-optimization`
>
> 目的：把 JADX 源码与设备 Clash 实际连接日志对应起来，确认 Joyose/PowerKeeper 的云配置、统计回传和网络出口。本文只做审计，不改变稳定版行为。

## 2026-09-12 设备网络日志结论

当前 Clash 日志中大量 Xiaomi/Google 相关连接被 `REJECT` 或 `REJECT-DROP`：

- `com.miui.analytics -> pubsub.googleapis.com:443`：`REJECT-DROP`
- `com.miui.analytics -> sdkconfig.intl.xiaomi.com:443`：`REJECT-DROP`
- `com.miui.android.fashiongallery -> sdkconfig.ad.intl.xiaomi.com:443`：`REJECT-DROP`
- `com.xiaomi.xmsf -> resolver.msg.global.xiaomi.net:443`：`REJECT-DROP`
- `com.xiaomi.xmsf -> firebaseremoteconfig.googleapis.com:443`：`REJECT-DROP`
- `com.miui.cloudbackup -> appbackupapi.micloud.xiaomi.net:443`：`REJECT`

在这段日志中没有看到 `com.xiaomi.joyose` 或 `com.miui.powerkeeper` 直接建立外网 TCP 连接。

这只能证明这些日志时间段内没有观察到它们直接联网，不能单凭这一点证明所有 Joyose/PowerKeeper 数据链都不存在；仍需考虑它们通过其他 Xiaomi 服务、系统组件或本地 IPC 转交的可能性。

## 1. Joyose 自身的云配置网络开关

`com.xiaomi.joyose.JoyoseApplication` 初始化 TEG CloudConfig 后立即执行：

```java
CloudConfig.init(getApplicationContext());
CloudConfig.setNetworkAccessEnabled(false);
```

也就是说，Joyose 默认先关闭 TEG 云配置 SDK 的网络访问。

真正的开关由 `JoyoseCloudControlManager3` 根据以下条件计算：

1. `device_provisioned` 已完成；
2. 当前网络满足 Wi-Fi/非计费网络条件；
3. `persist.sys.sc_allow_conn` 为 true。

源码对应逻辑最终调用：

```java
CloudConfig.setNetworkAccessEnabled(z2);
```

因此 Joyose 并不是无条件持续联网，而是有明确的本地网络许可门控。

## 2. Joyose 的 MCC 云配置地址

TEG `com.xiaomi.teg.config.f` 明确包含：

```text
https://mcc.inf.miui.com/cloud/app/getData
https://mcc.inf.miui.com/cloud/app/uploadData
https://mcc.intl.inf.miui.com/cloud/app/getData
https://mcc.intl.inf.miui.com/cloud/app/uploadData
https://mcc.india.inf.miui.com/...
https://mcc.russia.inf.miui.com/...
```

具体区域由国际版/地区设置选择。

## 3. getData 实际发送的数据

`cloud/app/getData` 的请求参数来自 `f(long version)`，包括：

- `packageName`
- `channel`（存在时）
- `appVersion`
- `versionName`（存在时）
- `deviceInfo`
- 本地配置版本 `version`

其中 `deviceInfo` 由 JSON 构造，包含：

- `ihash`
- `uid`
- `d` = `Build.DEVICE`
- `r` = region
- `l` = language/locale
- `v` = MIUI/系统版本相关字段
- 国际版条件下额外包含 `bv`、`t`
- `av`
- `p`

所以源码可以确认：**MCC 云配置请求会携带设备/版本上下文，而不是单纯发送一个配置版本号。**

## 4. uploadData 的性质

`com.xiaomi.teg.config.f` 还明确实现：

```text
cloud/app/uploadData
```

该接口在配置更新成功后被调用。

上传结构包括前述 `packageName/channel/appVersion/versionName/deviceInfo`，并额外加入 `ar` 数组。`ar` 中每项包含：

```text
s = status
 d = ruleId
 m = moduleKey
 v = version
```

源码日志字符串为：

```text
send analytic back to server
```

因此可以明确判断：**TEG 云配置 SDK 存在“配置拉取 + 配置处理结果回传”的分析数据通道。**

这与 OneTrack/Analytics SDK 是不同的一条链，不应混为一谈。

## 5. 当前最重要的事实：Joyose 是否实际打开这条链

从源码看，答案不是“永远打开”：

```text
JoyoseApplication
  -> CloudConfig.init()
  -> setNetworkAccessEnabled(false)

JoyoseCloudControlManager3
  -> 判断 device_provisioned
  -> 判断网络条件
  -> 判断 persist.sys.sc_allow_conn
  -> setNetworkAccessEnabled(true/false)
```

因此实际设备上是否发生 MCC 请求，应以运行时网络日志为准。

目前提供的 Clash 日志没有出现 `com.xiaomi.joyose` 的直接 MCC 连接，说明至少在这段采样时间里没有观察到该应用直接向 MCC 建立 TCP 连接。

## 6. PowerKeeper / mcd 不是等价于“上传”

PowerKeeper/Joyose 中存在：

```text
/data/system/whetstone/perf_data
mcd.extra.params
ctl.start = mcd_init
```

其中 `z.l` 的 `gameBoosterRun()` 会生成/写入 `perf_data`，完成后通过 `mcd_init` 触发更底层的处理。

这条链首先是**性能命令执行链**，不能因为出现 `mcd` 就认定它是网络遥测上传。

当前应把它与网络上传链分开审计。

## 7. 下一步：闭环确认，而不是继续扩大拦截

按照证据优先级继续：

1. 查 `com.xiaomi.joyose` 是否实际调用 `CloudConfig.updateData()`，并记录调用时机。
2. 查 TEG `uploadData` 的唯一调用点，确认哪些配置更新会触发回传。
3. 查 `ThermalInfoHelper`/Thermal IEC 数据是否最终进入其他 Xiaomi 服务或网络上传器。
4. 查 PowerKeeper `ThermalLogUploader` 的调用条件和目标服务器。
5. 查 Joyose/PowerKeeper 是否直接使用 OneTrack；当前对 Joyose/PowerKeeper 包的静态搜索没有发现直接 `OneTrack` 调用。
6. 最后把运行时 UID/域名与源码调用链逐一对应。

### 当前安全结论

- Clash 的 `REJECT/REJECT-DROP` 能阻断对应网络连接，服务器无法通过这些被拒绝的连接正常接收数据。
- 当前日志没有观察到 Joyose/PowerKeeper 直接外联。
- 源码已经证明 TEG 存在 MCC `getData` 和 `uploadData`，以及设备上下文和配置处理结果回传。
- 还不能宣称“所有 Joyose/PowerKeeper 遥测已经被彻底消除”，因为需要继续排除经 `com.miui.analytics`、`com.xiaomi.xmsf`、其他服务或本地 IPC 转交的路径。

**原则：先闭环证据，再决定是否修改 Hook。**
