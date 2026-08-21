#!/bin/bash
# =============================================================
# 自动故障切换脚本 (飞牛 NAS)
# 位置: /volume2/erp-system/failover.sh
# 作用: 检测主站故障, 自动启动备用服务, 切换 DNS
# 用法: ./failover.sh {check|trigger|rollback|status}
# =============================================================
set -e

# 配置
MAIN_URL="${MAIN_URL:-https://home.93gushi.com:8088/api/auth/captcha}"
HEALTH_CHECK_TIMEOUT="${HEALTH_CHECK_TIMEOUT:-10}"
FAIL_THRESHOLD="${FAIL_THRESHOLD:-3}"           # 连续失败次数触发切换
HEALTH_CHECK_INTERVAL="${HEALTH_CHECK_INTERVAL:-60}"
STATE_FILE="${STATE_FILE:-/volume2/erp-system/.failover-state}"
LOG_FILE="${LOG_FILE:-/var/log/failover.log}"
WECOM_WEBHOOK="${WECOM_WEBHOOK:-https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=your_key}"

# 状态机
STATE_NORMAL="NORMAL"
STATE_FAILING="FAILING"
STATE_FAILED="FAILED"
STATE_RECOVERING="RECOVERING"

# 初始化状态
if [ ! -f "$STATE_FILE" ]; then
  echo "$STATE_NORMAL" > "$STATE_FILE"
fi

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') [$1] $2" | tee -a "$LOG_FILE"
}

notify() {
  local LEVEL="$1"
  local MSG="$2"
  log "$LEVEL" "$MSG"

  # 发送企业微信通知
  if [ -n "$WECOM_WEBHOOK" ] && [ "$WECOM_WEBHOOK" != "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=your_key" ]; then
    curl -s -X POST "$WECOM_WEBHOOK" \
      -H "Content-Type: application/json" \
      -d "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"## ERP 热备告警\\n**级别**: $LEVEL\\n**时间**: $(date)\\n**消息**: $MSG\"}}" \
      > /dev/null 2>&1 || true
  fi
}

# 检测主站健康
check_main() {
  local CODE=$(curl -sk -o /dev/null -w "%{http_code}" \
    --max-time "$HEALTH_CHECK_TIMEOUT" "$MAIN_URL" 2>/dev/null || echo "000")

  if [ "$CODE" = "200" ]; then
    return 0  # 健康
  else
    return 1  # 故障
  fi
}

# 检测备用服务健康
check_standby() {
  local CODE=$(curl -sk -o /dev/null -w "%{http_code}" \
    --max-time "$HEALTH_CHECK_TIMEOUT" \
    "http://localhost:8080/api/auth/captcha" 2>/dev/null || echo "000")

  if [ "$CODE" = "200" ]; then
    return 0
  else
    return 1
  fi
}

# 启动备用服务
start_standby() {
  log "INFO" "启动备用服务..."

  # 启动 backend, pc-web, app-h5 (但不启动 mysql/redis, 它们已 standby)
  cd /volume2/erp-system
  docker compose -f docker-compose.failover.yml \
    --profile failover up -d erp-backend-failover erp-pc-web-failover

  # 等待 backend 健康
  log "INFO" "等待 Backend 启动..."
  for i in {1..30}; do
    if check_standby; then
      log "INFO" "Backend 已就绪"
      return 0
    fi
    sleep 5
  done

  log "ERROR" "Backend 启动超时"
  return 1
}

# 停止备用服务
stop_standby() {
  log "INFO" "停止备用服务..."
  cd /volume2/erp-system
  docker compose -f docker-compose.failover.yml \
    --profile failover down
}

# 触发故障切换
trigger_failover() {
  local STATE=$(cat "$STATE_FILE")

  if [ "$STATE" = "$STATE_FAILED" ]; then
    log "WARN" "已在 FAILED 状态, 跳过重复切换"
    return 0
  fi

  log "INFO" "触发故障切换!"
  notify "CRITICAL" "ERP 主站 (home.93gushi.com) 不可访问, 正在切换到飞牛备用服务器"

  # 1. 启动备用服务
  if ! start_standby; then
    log "ERROR" "备用服务启动失败, 切换中止"
    notify "CRITICAL" "ERP 备用服务启动失败, 需要人工介入"
    echo "$STATE_FAILED" > "$STATE_FILE"
    return 1
  fi

  # 2. 切换 DNS
  log "INFO" "切换 DNS..."
  /opt/industrial-erp/scripts/dns-switch.sh to-backup

  # 3. 更新状态
  echo "$STATE_FAILED" > "$STATE_FILE"
  notify "INFO" "ERP 已成功切换到备用服务器: n150.93gushi.com:8088"
}

# 回切到主站
rollback() {
  local STATE=$(cat "$STATE_FILE")

  if [ "$STATE" = "$STATE_NORMAL" ]; then
    log "INFO" "已在 NORMAL 状态, 无需回切"
    return 0
  fi

  log "INFO" "回切到主站..."

  # 验证主站恢复
  if ! check_main; then
    log "WARN" "主站仍不可访问, 暂不切换"
    return 1
  fi

  # 1. 切换 DNS 回主站
  log "INFO" "切换 DNS 回主站..."
  /opt/industrial-erp/scripts/dns-switch.sh to-main

  # 2. 等待 DNS 生效 (15 分钟)
  log "INFO" "等待 DNS 缓存过期 (15 分钟)..."
  sleep 900

  # 3. 停止备用服务
  log "INFO" "停止备用服务..."
  stop_standby

  # 4. 更新状态
  echo "$STATE_NORMAL" > "$STATE_FILE"
  notify "INFO" "ERP 已回切到主站: home.93gushi.com"
}

# 健康检查循环 (持续运行)
check_loop() {
  local FAIL_COUNT=0

  while true; do
    if check_main; then
      # 主站健康
      FAIL_COUNT=0
      local STATE=$(cat "$STATE_FILE")

      if [ "$STATE" = "$STATE_FAILED" ]; then
        log "INFO" "主站恢复, 准备回切"
        echo "$STATE_RECOVERING" > "$STATE_FILE"
        rollback
      fi
    else
      # 主站故障
      FAIL_COUNT=$((FAIL_COUNT + 1))
      log "WARN" "主站不可访问 (第 $FAIL_COUNT/$FAIL_THRESHOLD 次)"

      if [ $FAIL_COUNT -ge $FAIL_THRESHOLD ]; then
        trigger_failover
      fi
    fi

    sleep "$HEALTH_CHECK_INTERVAL"
  done
}

# 主入口
case "${1:-check}" in
  check)
    # 单次检查
    if check_main; then
      echo "MAIN: OK"
      exit 0
    else
      echo "MAIN: FAIL"
      exit 1
    fi
    ;;
  loop)
    check_loop
    ;;
  trigger)
    trigger_failover
    ;;
  rollback)
    rollback
    ;;
  status)
    echo "当前状态: $(cat $STATE_FILE)"
    docker ps --format "table {{.Names}}\t{{.Status}}" | grep -E "erp-|failover"
    ;;
  *)
    echo "Usage: $0 {check|loop|trigger|rollback|status}"
    exit 1
    ;;
esac
