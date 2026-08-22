#!/bin/bash
# =============================================================
# MySQL 主从复制初始化脚本 (飞牛 NAS 启动时自动执行)
# 位置: /volume2/erp-system/init-mysql-replica.sh
# 作用: 自动 CHANGE MASTER TO 主库, 启动 SLAVE
# =============================================================
set -e

# 配置 (从环境变量传入, 或修改默认值)
MASTER_HOST="${MYSQL_MASTER_HOST:-192.168.0.150}"
MASTER_PORT="${MYSQL_MASTER_PORT:-3306}"
MASTER_USER="${MYSQL_REPL_USER:-repl_user}"
MASTER_PASSWORD="${MYSQL_REPL_PASSWORD:-Repl_2026_Strong!}"
LOG_FILE="${MYSQL_LOG_FILE:-binlog.000019}"
LOG_POS="${MYSQL_LOG_POS:-1893}"

echo "=========================================="
echo " MySQL 主从复制初始化"
echo "时间: $(date)"
echo "主库: ${MASTER_HOST}:${MASTER_PORT}"
echo "=========================================="

# 1. 配置主库 (在主 NAS 上执行, 这里仅打印)
cat << 'EOF'
[主库配置] (在主 NAS 192.168.0.150 上执行)
mysql -uroot -perp_root_pwd << 'SQL'
-- 创建复制用户
CREATE USER 'repl_user'@'%' IDENTIFIED BY 'Repl_2026_Strong!';
GRANT REPLICATION SLAVE ON *.* TO 'repl_user'@'%';
FLUSH PRIVILEGES;

-- 启用 binlog (主库 my.cnf 已配置)
-- server-id=1
-- log-bin=mysql-bin
-- binlog-format=ROW
SHOW MASTER STATUS;
SQL
EOF

# 2. 在备用库执行 (当前 MySQL 容器)
mysql -uroot -perp_root_pwd << SQL
-- 停止现有 slave
STOP SLAVE;
RESET SLAVE;

-- 配置主库连接
CHANGE MASTER TO
  MASTER_HOST='${MASTER_HOST}',
  MASTER_PORT=${MASTER_PORT},
  MASTER_USER='${MASTER_USER}',
  MASTER_PASSWORD='${MASTER_PASSWORD}',
  MASTER_LOG_FILE='${LOG_FILE}',
  MASTER_LOG_POS=${LOG_POS},
  MASTER_CONNECT_RETRY=10;

-- 启动 slave
START SLAVE;

-- 查看状态
SHOW SLAVE STATUS\G
SQL

echo "=========================================="
echo " MySQL 主从复制配置完成"
echo "=========================================="

# 3. 持续监控 slave 状态
while true; do
  STATUS=$(mysql -uroot -perp_root_pwd -e "SHOW SLAVE STATUS\G" 2>/dev/null | grep "Slave_IO_Running\|Slave_SQL_Running" | awk '{print $2}')
  IO=$(echo "$STATUS" | head -1)
  SQL=$(echo "$STATUS" | tail -1)

  if [ "$IO" != "Yes" ] || [ "$SQL" != "Yes" ]; then
    echo "$(date) [ALERT] Slave 状态异常: IO_Running=$IO, SQL_Running=$SQL" >> /var/log/mysql-replica.log
    # 发送告警 (可选)
  fi
  sleep 60
done
