# 0008. ESP32 设备上报鉴权: 固定密钥免登录态, 不走 Sa-Token

- **状态**: 采纳 (v1.1.119 ESP32 米克重采集)
- **关联**: `DeviceWeightReportController` / `SaTokenConfig` 白名单 / `ERP_DEVICE_KEY` / 双站部署 (ADR-0005/0006)

## 背景

ESP32 现场无人值守, 每 5s 向 ERP 上报吹膜机米克重采样。若走 Sa-Token 登录态: token 30 天 timeout + 并发共享, 现场 ESP32 刷新不了, 会**静默变成 11012 未登录**——设备跑几天就悄悄断数据, 且没报错。

## 决定

- **设备上报走固定密钥自校验, 不用 Sa-Token**: `POST /device/weight/report` 在 `SaTokenConfig` 登录态豁免口里**只写精确路径 `/device/weight/report`** (绝不写 `/device/**`——那个 notMatch 是全项目唯一登录态豁免口, 写通配等于把设备端所有端点裸奔到公网)。
- **密钥走 env `ERP_DEVICE_KEY`** (`@Value` 注入), **不硬编码不落库**; 表 `mes_weight_device.device_key_masked` 只存后 6 位掩码。
- **fail-closed**: key 未配置 = 拒绝一切上报 (`500`), 不静默放行。
- **双站用不同 key** (canary-first, ADR-0005): 各站 `.env` 各存各的 key, 密钥不进 git/镜像。
- **ESP32 不发 Origin 头 → 不配 CORS** (CORS 是浏览器概念, 对设备无意义)。
- **PC 查询路径 (`/mes/weight/**`) 保持带登录态 + `mes:weight:*` perm**, 与设备路径严格分离, 不混。

## 备选 (未选)

- **Sa-Token 设备 token**: 现场无人值守刷不了 30 天 token, 静默断数据。拒。
- **设备端登录 + 定时续期**: ESP32 固件复杂度高, 断网续不上。拒。
- **开放 `/device/**` 通配豁免**: 把设备端全端点裸奔到公网, 安全风险不可接受。拒。

## 后果

- `SaTokenConfig` 白名单多一行精确路径 —— 维护者**必须记住只加精确路径**, 这是全项目登录态豁免的唯一口子 (见 [0007](0007-app-runtime-probe-and-cors-origin.md) CORS 同源教训)。
- `ERP_DEVICE_KEY` 是新增必填 env (留空 = 功能失效, 但不是故障——fail-closed 是设计)。
- 设备侧时钟 (`collect_time`) 靠 ESP32 NTP/启动外推, 后端不校 (信任设备时钟, 有偏差在数据侧处理)。
