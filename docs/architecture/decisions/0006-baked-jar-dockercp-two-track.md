# 0006. 后端 jar 双站双轨部署: home baked-in / 飞牛 runtime mount

- **状态**: 采纳 (持续维护)
- **首次落地**: v1.1.65
- **关联**: R17 / 双站 Dockerfile 差异 / `erp-nas-deploy-jar-quirk` memory

## 背景

两站 Dockerfile 对后端 jar 的处理方式不同，导致同一个"更新 jar"动作在两站的正确做法不一样，踩错会让 `docker restart` 拉起旧 jar 或不生效。

## 决定

| 站 | Dockerfile | 更新 jar 正确路径 |
|---|---|---|
| home (192.168.0.150) | `COPY industrial-erp-*.jar app.jar` **baked-in** | `docker build --no-cache` + `docker rm -f` + `docker run` 三连；单纯 `docker restart` 不拉新 jar |
| 飞牛 (192.168.0.32) | `COPY erp-backend.jar`（运行时可 mount/覆盖） | 新 jar 放 host `/vol2/erp-system/backend/erp-backend.jar` + `docker cp` 覆盖容器写层 + `docker restart` |

- 两站 jar 文件名不同（`industrial-erp-*.jar` vs `erp-backend.jar`），BuildKit COPY 层缓存可能命中旧包 → 强制覆盖用 `docker cp`。
- 换 `SA_TOKEN_JWT_SECRET_KEY`（带 `SecurityPreflightValidator` 的 jar 首次）→ token 全失效需重登，重建容器时保留各自 env。

## 后果

- 部署前先判断"当前是哪站 + 哪种 Dockerfile"，选对应路径。
- 验证 jar 是否真更新：`docker exec ... md5sum /opt/app/app.jar` 对新 jar 本地 md5（baked-in 用 `--entrypoint md5sum` 探容器层）。
