# 0005. 双站部署: 飞牛 canary 先行 24h, 再同步 home

- **状态**: 采纳 (长期规则, v1.1.79 起用户拍板)
- **关联**: home 192.168.0.150 / 飞牛 canary 192.168.0.32 / R 部署顺序

## 背景

双站各跑一份 `industrial_erp`。home 是生产数据主站（baked-in jar 镜像），飞牛是可丢弃的预发。直接改 home 有风险；先上飞牛验证，出错影响面小、可丢弃重灌。

## 决定

- 每次 ERP 更新：**先部署飞牛 canary，跑 24h+ 无问题，再同步 home**。
- 后端 jar：飞牛 `mvn clean package` → `cat|ssh` 流式传（校验 md5，勿踩 corrupt jar 坑）→ `docker cp` 覆盖 `erp-backend-failover:/opt/app/app.jar` 写层 → `docker restart`（见 [0006](0006-baked-jar-dockercp-two-track.md)）。
- 前端 dist：飞牛 bind-mount 源 `/vol2/erp-system/pc-web/dist-new` 全量替换 + `docker restart erp-pc-web-failover`（非 COPY，重建镜像会回退）。
- 验证：`/api/auth/captcha` 200 + 关键端点路由 401（非 404）+ dist chunk 在线 grep 版本字面量。
- SQL seed：飞牛先灌，确认 `information_schema` 幂等命中后再同步 home。

## 后果

- 部署清单里常出现"飞牛 canary 部署完成，home 待批准"字样——这是正常中间态，不是遗漏。
- 双站数据靠复制 + 手动 SOURCE 同步，漏灌列表现为"home 某功能 500 / 飞牛正常"；用 `scripts/sql-drift-check.sh`（v1.1.104）定期巡检漂移。
