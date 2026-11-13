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

### 仓库明文口令: 现状 (2026-11-13 更新)

仓库跟踪文件里的明文运维口令**已清零**。`grep -rE '***REMOVED_CREDENTIAL***|***REMOVED_CREDENTIAL***|***REMOVED_CREDENTIAL***|<第三方 key>'` 在仓库根下零命中; 脚本与 compose 改成读环境变量 / `${VAR:?}` 引用, 凭据真值只落在两处:

| 载体 | 位置 | 权限 |
|---|---|---|
| 本机凭据文件 | `~/.config/erp-ops/env` | `600`, 只在本机, **不入版本库** |
| 各站运行时 `.env` | `.150` `/volume3/docker/erp-system/.env`、`.32` `/vol2/erp-system/.env` | `600`, 只在对应 NAS 上 |

> ⚠️ **git 历史里仍有旧值**。改文件不会让历史里的口令消失 ⇒ 唯一有效的彻底处置是 `git filter-repo` 重写历史 (需 force-push, 必须本人确认), 或者**把那些口令本身换掉** —— 后者已在 2026-11-13 完成 (见下一节)。

### 生产凭据轮换 (2026-11-13 实做)

轮换范围: `.150` (主库) / `.32` (热备) 的 **MySQL root 口令**, 以及两站 NAS 磁盘上的明文残留。

> **SSH 口令固定, 不得再轮换**: 两站 `gpssong` 的 SSH/sudo 口令是运维人日常使用的凭据, **本轮轮换后已按要求改回原值** (`.150` 与 `.32` 各自原值)。它们的价值在「人记得住」, 强度不作为轮换目标 —— 以后**不再对这两个口令做轮换**; 需要提权时从 `~/.config/erp-ops/env` 取值即可。
>
> 唯一的例外: 若怀疑口令已泄漏, 应**先轮换再通知运维人改回他熟悉的那一套**, 而不是把弱口令当成常态保护。

**MySQL root —— 双口令窗口的正确开法**

```sql
-- 1) 开窗口: 新旧两个口令同时有效
ALTER USER 'root'@'%' IDENTIFIED BY '<新口令>' RETAIN CURRENT PASSWORD;
ALTER USER 'root'@'localhost' IDENTIFIED BY '<新口令>' RETAIN CURRENT PASSWORD;
-- 2) 换完所有消费者后关窗口 (不可逆, 但可回滚: IDENTIFIED BY '<旧口令>' 会把旧口令设回当前口令)
ALTER USER 'root'@'%' DISCARD OLD PASSWORD;
ALTER USER 'root'@'localhost' DISCARD OLD PASSWORD;
```

顺序不能颠倒: **先让所有消费者 (容器 env、备份脚本、复制脚本) 用上新口令, 再关窗口**。容器 env 是**建容器时烘进去的** —— 改 `.env` 不会影响运行中的容器, 必须重建容器, 否则关窗口的瞬间应用就断。

**DSM (群晖) 改 SSH 口令 —— 必须走 `synouser`**

用 `chpasswd` 直接改 `/etc/shadow` 会被 DSM 的账号库同步覆盖回去 (实测: 改完当时能登录, 十几分钟后口令自己变回旧值)。唯一稳的做法是 DSM 原生接口:

```bash
sudo /usr/syno/sbin/synouser --setpw <user> '<新口令>'
sudo /usr/syno/sbin/synouser --login <user> '<新口令>'   # 校验账号库里存的就是这个
```

**NAS 侧去明文口径**

- compose 里不写口令字面值, 一律 `${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD_MUST_BE_SET}`, 值放同目录 `.env` (`600`)。
- 脚本从 `.env` 取值, 不硬编码; 文档与 SQL 注释里的示例命令写 `-p"${MYSQL_ROOT_PASSWORD}"`。
- 轮换完把 NAS 上残留的旧字面量逐文件替换掉 (本次 `.150` 95 个文件 / `.32` 56 个文件), 替换完再全盘 `grep` 复查应为 0。

### 历史遗留 (git 历史重写, 待本人确认)

| 项 | 状态 |
|---|---|
| 仓库工作区明文口令 | ✅ 已清零 (2026-11-13) |
| 两台 NAS 磁盘上的明文口令 | ✅ 已清零 (2026-11-13) |
| 阿里云 MySQL root / 复制账号 / 飞鹅账号口令 | ✅ 从来不在跟踪文件里, 只在各自 `.env` |
| **git 历史里的旧值** | ⏳ 仍需 `git filter-repo` 重写 (force-push, 需本人确认) |
| **飞鹅 UKEY** | ⏳ 见上面「待办」段, 需在飞鹅后台重置凭证本身 |

口令本身已在 2026-11-13 轮换过 (MySQL root 与两站 SSH), 飞鹅 UKEY 是最后一项。

