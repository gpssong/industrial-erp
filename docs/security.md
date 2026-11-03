# v1.1.49: 安全配置文档

## .env 必填项与生成命令

启动前 **必须** 设置以下变量, 留空或用占位值将导致 backend 启动失败 (`SecurityPreflightValidator`):

| 变量 | 生成命令 | 强度要求 | 作用 |
|---|---|---|---|
| `MYSQL_ROOT_PASSWORD` | `openssl rand -base64 24` | ≥16 字符, 含大小写 + 数字 | MySQL root 密码, 同时影响 `SPRING_DATASOURCE_PASSWORD` |
| `SA_TOKEN_JWT_SECRET_KEY` | `openssl rand -hex 32` | ≥32 字符, 不在黑名单 | JWT 签名密钥, 用于鉴权 |
| `FEIE_DEFAULT_USER` | 飞鹅云后台查 | - | 飞鹅云打印默认账号 |

## 已知弱密钥黑名单 (v1.1.49 起拒绝启动)

- `industrial-erp-jwt-test-p2-fix`
- `erp_jwt_secret_2026_gpssong_xxxx`
- `changeme` / `change-me` / `secret` / `default` / `test` / `12345678` / `password`

## 升级流程 (.env 改动后)

```bash
# 1. 备份当前 .env (时间戳后缀)
cp .env .env.bak.$(date +%Y%m%d_%H%M%S)

# 2. 用编辑器改 .env, 确认变更
diff .env .env.bak.<上次>

# 3. 应用新环境变量 (backend 重启生效)
docker compose restart backend

# 4. 验证启动日志
docker compose logs --tail=50 backend
```

## 密钥泄漏后果

| 泄漏项 | 后果 |
|---|---|
| `SA_TOKEN_JWT_SECRET_KEY` | 攻击者可伪造 token 越权访问 API |
| `MYSQL_ROOT_PASSWORD` | 攻击者可直连 MySQL 导出/篡改数据 |

## 安全加固建议 (P2 未实现)

- [ ] 启用 HTTPS (NAS DSM 反向代理已支持, 但容器内 nginx 仍是 HTTP)
- [ ] 把 JWT secret / MySQL 密码放入 Docker secrets (`docker stack deploy`)
- [ ] 定期轮换密钥 (每次部署换一次, 不保留 .env.bak > 30 天)
- [ ] 生产 .env 加入 `.gitignore` 时同时加 `*.bak.*` 防止泄漏

## 仓库明文凭据清理 (2026-11-03)

**背景**: 排查 v1.1.165 提交面时发现仓库里存在第三方服务明文凭证, 需要区分「新增泄漏」和「历史遗留」。

### 本次已处理

| 项 | 位置 | 处理 |
|---|---|---|
| TextIn `x-ti-app-id` / `x-ti-secret-code` | `backend/src/test/.../IntegrationSecretMergeTest.java` | 换成明显假的占位 fixture (`aaaa…` / `deadbeef…`), 单测 5/5 仍绿。**未进过任何提交** |
| 飞鹅云 UKEY | `docs/CHANGELOG.md` 2 处 | 改为「UKEY 已脱敏（真值在飞鹅控制台与库内 `sys_feie_printer_config`）」 |
| 整库 dump | `backups/pre_v121_full_20260825_161507.sql` (1.5 MB) | `git rm --cached` 停止跟踪, **磁盘文件保留** (v1.2.1 前唯一一份全量备份); `.gitignore` 早有 `backups/*.sql`, 只是对已跟踪文件不生效 |

### 待办: 飞鹅 UKEY 轮换

明文 UKEY 已提交进 git 历史, 改文件不会让历史里的值消失 ⇒ **唯一有效处置是换掉凭证本身**:

1. 飞鹅云后台 (`api.feieyun.cn` 对应账号) 重置 UKEY。
2. 更新库内配置 — UKEY 运行时只从表里取, **没有任何 env 兜底** (`application.yml` 里不含 `FEIE_*`; `FEIE_DEFAULT_USER` 只是 `testConnection()` 里飞鹅**账号邮箱**的兜底, 与 UKEY 无关):
   表 `sys_feie_printer_config.ukey`, 打印消费点 `FeiePrintService.java:168` 的 `config.getUkey()`。
3. `sys_feie_printer_config` 可能有多台打印机 (`FeiePrintController.listPrinters()` 返回 List) —— 逐台按其 `device_sn` 更新对应行的 `ukey`, 不要只改一行。
4. 只改 `.150` 主库, 复制会自动带到 `.32`; 阿里云按既有回切流程对齐。
5. 改完用一张真实工单走一次 App 飞鹅打印验证 (旧 UKEY 会静默失败, 界面只看得到「打印成功」的假象)。

### 历史遗留 (未清理, 需决策)

以下是**仓库第一天就带进来的运维口令**, 数量大且分散, 本轮**没有动**:

| 凭据 | 出现范围 (跟踪文件) |
|---|---|
| 飞牛 NAS 的 SSH 口令 | `docker/failover-fn/README.md`、`RUNBOOK.md`、`docs/CHANGELOG.md` (10 处)、`scripts/entity-schema-check.py`、`sql/101_*.sql` |
| home/群晖 NAS 的 SSH 口令 | `scripts/deploy-home-dist.sh`、`deploy-version-bump.sh`、`restart-pc-web.sh`、`scripts/entity-schema-check.py`、`docs/CHANGELOG.md` (13 处) |
| MySQL root 口令 (三站) | 约 50 个跟踪文件 (`docker-compose*.yml`、`scripts/*.sh`、`sql/*.sql` 注释、docs) |

> 本节刻意不写出这些口令的字面值 —— 记录明文凭据的文档本身不该再往里印一遍。需要取值时看各站 `.env` 与 `docker/failover-fn/` 的环境文件。

- 为什么没顺手一起改: 这些值多数是**脚本里真实在用**的 (`sshpass -p '…'`), 单改成 `****` 会让部署脚本直接坏掉, 必须先逐个改成读 env / 读 `.env` 才能脱敏 —— 是一次独立的改造工作, 不该混在文档提交里。
- 阿里云 MySQL root、主备复制账号口令、飞鹅账号密码 **不在**跟踪文件里, 只在各自机器的 `.env` / compose 环境文件中。
- 真要做, 顺序建议: 脚本改读 env → 三站 `.env` 补齐 → 轮换 SSH/root 口令 → 再逐文件脱敏 + `git filter-repo` 重写历史 (需 force-push, 必须本人确认)。
