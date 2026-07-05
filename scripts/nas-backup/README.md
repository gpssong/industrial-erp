# NAS 每日自动备份 (DS918+ / DSM 7.4)

> 生产环境数据库 + 后端 jar + 前端 dist 的每日 03:00 自动备份,30 天滚动保留。
> 出问题可一键恢复到任意时间点。

---

## 目录结构

```
/volume3/erp-backup/
├── db/                     # MySQL 全量 dump (gzip, 30 天滚动)
├── backend/                # industrial-erp-*.jar (仅变更时复制, 保留 10 份)
├── frontend/
│   ├── pc-web_*.tar.gz     # 仅变更时打, 保留 10 份
│   └── app-h5_*.tar.gz
├── meta/
│   ├── version_<TS>.json   # 本次备份元信息 (DB 大小/各文件名/host)
│   ├── backend.sha256      # 最新 jar hash, 用于变更检测
│   ├── pcweb.sha256
│   ├── apph5.sha256
│   └── latest.json → version_<TS>.json  (软链)
└── (脚本目录) /volume3/docker/erp-system/scripts/nas-backup/
    ├── backup.sh           # 执行备份
    ├── restore.sh          # 一键恢复
    ├── list.sh             # 列出当前所有备份
    └── logs/               # 每次执行的日志 (按日期)
```

---

## 一次性部署

### 1. 上传脚本到 NAS

脚本已部署在 `/volume3/docker/erp-system/scripts/nas-backup/`(2026-07-05 验证完毕)。

如果换机器需要重新上传:

```bash
# 本机执行
cd /Users/tongban/Documents/根据前端开发erp\ 2/erp-system/scripts/nas-backup
tar -cf - . | sshpass -p 'YOUR_PASS' ssh gpssong@192.168.0.150 \
  'sudo mkdir -p /volume3/docker/erp-system/scripts/nas-backup && \
   sudo tar -xf - -C /volume3/docker/erp-system/scripts/nas-backup && \
   sudo chmod +x /volume3/docker/erp-system/scripts/nas-backup/*.sh && \
   ls -la /volume3/docker/erp-system/scripts/nas-backup/'
```

### 2. 注册定时任务 (两种方式,任选其一)

#### 方式 A — DSM 控制面板 (推荐,GUI 操作)

**DSM 7.4 路径**: 控制面板 → 任务计划 → 新增 → 触发的任务 → 用户定义的脚本

| 字段 | 值 |
|---|---|
| 任务名称 | `ERP_DAILY_BACKUP` |
| 运行身份 | `root` |
| 事件 | `每天` 03:00 |
| 已启用 | ✓ |
| 脚本内容 | `/volume3/docker/erp-system/scripts/nas-backup/backup.sh` |
| 已发送运行详情 | 仅失败时(可选) |

#### 方式 B — `/etc/cron.d/` 一键脚本 (绕过 DSM GUI)

```bash
# ssh 登入群晖, sudo 提权执行
sudo /volume3/docker/erp-system/scripts/nas-backup/install_cron.sh
```

会在 `/etc/cron.d/erp_daily_backup` 写入一条 cron 任务:

```
0 3 * * * root /volume3/docker/erp-system/scripts/nas-backup/backup.sh >> .../cron.log 2>&1
```

卸载: `sudo rm /etc/cron.d/erp_daily_backup`

**两种方式选一种即可,不要双注册**(会重复跑备份)。A 走 DSM 任务计划系统,能推送通知;B 用 cron 守护进程,与 DSM 解耦,DSM 重启也能跑。

### 3. 立即手动跑一次验证

```bash
ssh gpssong@192.168.0.150 \
  '/volume3/docker/erp-system/scripts/nas-backup/backup.sh'
# 预期输出末尾: ===== ERP 备份成功完成 (DB=xx MB) =====

# 看列表
ssh gpssong@192.168.0.150 \
  '/volume3/docker/erp-system/scripts/nas-backup/list.sh'
```

### 4. 挂 DSM 通知(可选)

控制面板 → 通知规则 → 新增 → 触发任务 `ERP_DAILY_BACKUP` 完成时 / 失败时 → 发邮件/推送到微信

---

## 日常使用

### 看最新备份状态

```bash
ssh gpssong@192.168.0.150 'cat /volume3/erp-backup/latest.json | jq'
```

### 列出所有备份清单

```bash
ssh gpssong@192.168.0.150 'sudo /volume3/docker/erp-system/scripts/nas-backup/list.sh'
```

### 恢复到指定时间点

```bash
# 1) 列出候选
ssh gpssong@192.168.0.150 'sudo /volume3/docker/erp-system/scripts/nas-backup/list.sh'

# 2) 全量恢复(交互式)
ssh gpssong@192.168.0.150 \
  'sudo /volume3/docker/erp-system/scripts/nas-backup/restore.sh 20260705_180000'
# 会问 y/N, 恢复前自动 dump 当前库到 pre-restore_*.sql.gz

# 3) 只恢复 DB (不动后端)
ssh gpssong@192.168.0.150 \
  'sudo /volume3/docker/erp-system/scripts/nas-backup/restore.sh --db-only 20260705_180000'

# 4) 只升级后端 jar / 前端 dist (DB 保留)
ssh gpssong@192.168.0.150 \
  'sudo /volume3/docker/erp-system/scripts/nas-backup/restore.sh --bin-only 20260705_180000'
```

### 一键还原到 30 天内任意一天

```bash
# 从本地发起,把目标 TS 传过去
ssh gpssong@192.168.0.150 \
  'sudo /volume3/docker/erp-system/scripts/nas-backup/restore.sh 20260625_030000'
```

---

## 工作原理

### 备份流程

```
03:00 DSM 任务计划触发
    ↓
backup.sh
    ├─ 健康检查: docker ps | grep erp-mysql (容器存活?)
    ├─ MySQL dump:
    │    docker exec erp-mysql mysqldump ... → 容器内 /tmp/
    │    docker cp 出来到 /volume3/erp-backup/db/*.sql.gz
    ├─ 后端 jar: sha256 对比,变了才复制到 backend/
    ├─ 前端 dist: tar -czf 全目录 → frontend/, 变更才打
    ├─ 写 meta/version_<TS>.json
    │    {
    │      "ts": "20260705_180000",
    │      "db_file": "industrial_erp_20260705_180000.sql.gz",
    │      "db_size": "1.2M",
    │      "backend_jar": "industrial-erp_20260705_173000.jar",
    │      "pc_web":     "pc-web_20260705_173000.tar.gz",
    │      "app_h5":     "...",
    │      "db_total_kept": 30,
    │      "host": "bai918"
    │    }
    └─ 清理: 30 天前 DB / 保留最近 10 份 jar+dist
```

### 关键设计点

| 点 | 设计 |
|---|---|
| **变更检测** | jar/dist 用 sha256,内容相同不重打包,省磁盘 |
| **滚动保留** | DB 30 天(数据可能横跨一周比 zip),jar/dist 10 份(版本切换很少) |
| **跨容器 dump** | `docker exec erp-mysql mysqldump` 而不是宿主机 mysqldump(避免 mysql-client 依赖) |
| **失败保护** | mysqldump 失败 / 0 字节 → `fail()` 立即退出, 当晚任务标记失败并发通知 |
| **恢复确认** | restore.sh 必须输 y 才执行,DB 恢复前自动再 dump 一份当前库作安全点 |
| **避免 cp 干扰** | 后端 jar 替换后用 `docker compose restart backend`,前端用 tar 解包到 /usr/share/nginx/html/ |

---

## 排错

| 现象 | 排查 |
|---|---|
| 备份列表为空 | `ls /volume3/erp-backup/db/` 看是否真的写了 |
| DB 备份 0 字节 | `cat logs/backup_YYYYMMDD.log` 看 mysqldump 错误 |
| DSM 定时任务不跑 | 控制面板 → 任务计划 → 看上次执行结果 / 失败原因 |
| restore 时 mysql 不能连 | 容器内: `docker exec erp-mysql mysqladmin -uroot -perp_root_pwd ping` |
| `pigz: command not found` | 自动降级到 `gzip`,性能略差但不影响 |

---

## 后续增强(规划)

- [ ] 用 rclone 同步到阿里云 OSS 做异地备份
- [ ] prometheus exporter 暴露 backup_age_seconds 指标
- [ ] 关键业务表(库存/订单)按小时单独 dump 以缩短 RPO
- [ ] 集成 DSM Hyper Backup 任务
