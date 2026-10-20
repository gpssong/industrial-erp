#!/usr/bin/env bash
#
# scripts/sql-drift-check.sh — home / 飞牛 数据漂移巡检 (P1-D, 2026-10-19 home 检查改善项)
#
# 背景 (v1.1.99 教训):
#   双站 (home 192.168.0.150 + 飞牛 192.168.0.32) 各跑一份 industrial_erp, 列结构靠
#   手动 docker cp SQL + SOURCE 同步。漏灌一列 (如 base_product 的 food_grade 列) 就
#   表现为 "home 页面某功能 500 / 飞牛正常", 排查靠肉眼比表。本脚本把这件事固化:
#   从 sql/ 源码推导出"期望表+列清单", 拉 live DB 的 INFORMATION_SCHEMA 对比,
#   自动输出 "live 缺哪些表/列" 与 "live 多哪些表/列"。
#
# 用法:
#   ./scripts/sql-drift-check.sh                      # 默认连 home (192.168.0.150), 只报"缺"
#   ./scripts/sql-drift-check.sh --host 192.168.0.32  # 连飞牛
#   ./scripts/sql-drift-check.sh --extra               # 同时报"live 多出的表/列"
#   ./scripts/sql-drift-check.sh --dry-run             # 只推导期望清单, 不连库 (离线自检)
#   DB_USER=root DB_PWD=erp_root_pwd ./scripts/sql-drift-check.sh --host 192.168.0.150
#
# 环境变量 (可覆盖默认):
#   DB_HOST     默认 192.168.0.150
#   DB_PORT     默认 3306
#   DB_USER     默认 root
#   DB_PWD      默认 erp_root_pwd
#   DB_NAME     默认 industrial_erp
#
# 说明: 这是"报告型"脚本 — 要完整打印报告, 即使某个子检查 grep 无命中(返回 1) 也不该中断。
# 所以只用 set -u (抓未定义变量), 不用 set -e / pipefail 去挡 grep 的退出码。
set -uo pipefail

# ---------- 参数 ----------
DB_HOST="${DB_HOST:-192.168.0.150}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-root}"
DB_PWD="${DB_PWD:-erp_root_pwd}"
DB_NAME="${DB_NAME:-industrial_erp}"

DRY_RUN=0
SHOW_EXTRA=0
SQL_DIR="$(cd "$(dirname "$0")/.." && pwd)/sql"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)  DB_HOST="$2"; shift 2 ;;
    --port)  DB_PORT="$2"; shift 2 ;;
    --user)  DB_USER="$2"; shift 2 ;;
    --pwd)   DB_PWD="$2";  shift 2 ;;
    --db)    DB_NAME="$2"; shift 2 ;;
    --extra) SHOW_EXTRA=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

if [[ ! -d "$SQL_DIR" ]]; then
  echo "[ERROR] sql dir not found: $SQL_DIR" >&2; exit 1
fi

# ---------- 工具 ----------
# 提取 sql/ 里所有 CREATE TABLE 的表名 (排除备份表 / 迁移临时表, 它们不是业务表)
extract_tables() {
  grep -rhoiE 'CREATE TABLE (IF NOT EXISTS )?[`]?[a-z0-9_]+[`]?' "$SQL_DIR"/*.sql 2>/dev/null \
    | sed -E 's/.*[[:space:]]//; s/[`]//g' \
    | sort -u \
    | grep -ivE '^if$' \
    | grep -vE '_bak_v[0-9]+$' \
    | grep -vE 'migration_review$' \
    | grep -vE '^$' || true
}

# 提取 "某表.某列" 期望清单。覆盖两种来源:
#   1. CREATE TABLE `x` ( `c1` type, `c2` type, ... );  — 逐块抓列名
#   2. ALTER TABLE `x` ADD [COLUMN] `c` ...             — 抓被 ADD 的列
# 输出 "table.column", 每行一个, 去重。
#
# 实现 (纯 grep/sed, BSD/macOS 可移植, 不用 awk 的 index(正则)):
#   把 CREATE TABLE 块按行切出来, 每块只归属它自己那个表名, 再抓块内
#   "列名 类型" 的独立定义行 (行首标识符 + 类型关键字), 排除 KEY/PRIMARY/
#   CONSTRAINT/UNIQUE/FOREIGN/INDEX 约束行。
extract_columns() {
  local f t rest body
  for f in "$SQL_DIR"/*.sql; do
    [[ -f "$f" ]] || continue
    # --- (1) 逐块处理 CREATE TABLE ---
    # 每次从文件里取第一个 CREATE TABLE 到第一个分号收尾的块, 处理后删掉再循环。
    # 简化: 用 awk 只做 "按 ; 切块 + 打印表名标记" 的行缓冲, 但列名抓取仍用 grep。
    # 为避免复杂状态机, 直接: 逐行扫描, 维护 cur_table (进入 CREATE 表名 / 遇下一行非缩进重置)。
    # 下面用 while-read (shell 内建, 无 awk 正则限制):
    local cur_table=""
    while IFS= read -r line; do
      # 进入 CREATE TABLE 块: 记表名 (表名后到左括号 ( 之间可能隔空格)
      if printf '%s' "$line" | grep -qiE 'CREATE TABLE[[:space:]]+(IF NOT EXISTS[[:space:]]+)?[`]?[a-z0-9_]+[`]?[[:space:]]*\('; then
        cur_table="$(printf '%s' "$line" | grep -oiE 'CREATE TABLE[[:space:]]+(IF NOT EXISTS[[:space:]]+)?[`]?[a-z0-9_]+[`]?' | sed -E 's/CREATE TABLE[[:space:]]+(IF NOT EXISTS[[:space:]]+)?//i; s/[`]//g' | head -1)"
        continue
      fi
      # 列定义行: 行首 (可能缩进/反引号) 标识符 + 空格 + 类型关键字, 且不是约束行
      if [[ -n "$cur_table" ]] && \
         printf '%s' "$line" | grep -qE '^[[:space:]]+[`]?[a-z0-9_]+[[:space:]`]' && \
         ! printf '%s' "$line" | grep -qiE '^[[:space:]]*(PRIMARY|UNIQUE|KEY|CONSTRAINT|FOREIGN|INDEX|FULLTEXT)'; then
        # 列名 = 第一个空格前的 token, 去掉反引号
        local cname
        cname="$(printf '%s' "$line" | sed -E 's/^[[:space:]]*//; s/^[`]//; s/[`].*//; s/[[:space:]].*//')"
        # 只保留看起来是列名的 (全小写字母数字下划线, 非类型关键字本身)
        if printf '%s' "$cname" | grep -qE '^[a-z0-9_]+$' && \
           ! printf '%s' "$cname" | grep -qiE '^(varchar|char|text|int|integer|bigint|smallint|tinyint|decimal|numeric|double|float|datetime|timestamp|date|time|json|enum|set|blob|tinytext|mediumtext|longtext)$'; then
          printf '%s.%s\n' "$cur_table" "$cname"
        fi
      fi
    done < "$f"
    # --- (2) ALTER TABLE ADD [COLUMN] ---
    grep -oiE 'ALTER TABLE[[:space:]]+[`]?[a-z0-9_]+[`]?[[:space:]]+ADD( COLUMN)?[[:space:]]+[`]?[a-z0-9_]+[`]?' "$f" 2>/dev/null \
      | sed -E 's/ALTER TABLE[[:space:]]+[`]?([a-z0-9_]+)[`]?[[:space:]]+ADD( COLUMN)?[[:space:]]+[`]?([a-z0-9_]+)[`]?/\1.\3/gi'
  done | sort -u | grep -viE '\.(INDEX|PRIMARY|UNIQUE|KEY|CONSTRAINT|FOREIGN|CHECK)$' || true
}

# ---------- Step 1: 推导期望清单 ----------
expected_tables="$(extract_tables)"
expected_cols="$(extract_columns)"

echo "========================================================"
echo " sql-drift-check  @  ${DB_HOST}:${DB_PORT}/${DB_NAME}  (user=${DB_USER})"
echo "========================================================"
echo " 期望: $(echo "$expected_tables" | grep -c . || true) 张表, $(echo "$expected_cols" | grep -c . || true) 个列 (来自 sql/ 源码)"

if [[ $DRY_RUN -eq 1 ]]; then
  echo ""
  echo "[--dry-run] 不连库。期望表清单如下:"
  echo "$expected_tables"
  exit 0
fi

# ---------- Step 2: 拉 live 清单 ----------
command -v mysql >/dev/null 2>&1 || { echo "[ERROR] 本机没有 mysql 客户端" >&2; exit 2; }

MYSQL=(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PWD" "$DB_NAME" --default-character-set=utf8mb4 -N -B)

# 注意: 直接执行 MYSQL 数组 (不是 printf 回显) — 早期 bug 用了 printf 导致拿到字面字符串
# 列清单用 CONCAT 拼 "table.column", 与期望清单 (extract_columns 输出) 同构
live_tables="$("${MYSQL[@]}" -e "SELECT table_name FROM information_schema.tables WHERE table_schema='${DB_NAME}' AND table_type='BASE TABLE';" 2>/dev/null || true)"
live_cols="$("${MYSQL[@]}" -e "SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns WHERE table_schema='${DB_NAME}';" 2>/dev/null || true)"

if [[ -z "$live_tables" && -z "$live_cols" ]]; then
  echo "[ERROR] 拉 live INFORMATION_SCHEMA 失败 (连不上 ${DB_HOST}:${DB_PORT} 或密码错)" >&2
  exit 3
fi

# ---------- Step 3: diff ----------
# 从 stdin 读入, 归一化: 去掉空格/反引号, 去空行, 去重排序
norm() { tr -d ' `'| grep -vE '^$' | sort -u; }

missing_tables="$(comm -23 <(printf '%s' "$expected_tables" | norm) <(printf '%s' "$live_tables" | norm) || true)"
missing_cols="$(comm -23 <(printf '%s' "$expected_cols" | norm) <(printf '%s' "$live_cols" | norm) || true)"

echo ""
echo " live:  $(echo "$live_tables" | grep -c . || true) 张表, $(echo "$live_cols" | grep -c . || true) 个列"

if [[ -n "$missing_tables" ]]; then
  echo ""
  echo "🔴 live 缺表 (sql/ 有定义但 live 没有) — 需补灌:"
  echo "$missing_tables" | sed 's/^/    - /'
fi

if [[ -n "$missing_cols" ]]; then
  echo ""
  echo "🔴 live 缺列 (sql/ 有定义但 live 没有) — 需补灌:"
  echo "$missing_cols" | sed 's/^/    - /'
fi

if [[ $SHOW_EXTRA -eq 1 ]]; then
  extra_tables="$(comm -13 <(printf '%s' "$expected_tables" | norm) <(printf '%s' "$live_tables" | norm) || true)"
  extra_cols="$(comm -13 <(printf '%s' "$expected_cols" | norm) <(printf '%s' "$live_cols" | norm) || true)"
  if [[ -n "$extra_tables" ]]; then
    echo ""
    echo "🟡 live 多出的表 (sql/ 没定义, 可能是手工加的临时表) — 评估是否回收:"
    echo "$extra_tables" | sed 's/^/    + /'
  fi
  if [[ -n "$extra_cols" ]]; then
    echo ""
    echo "🟡 live 多出的列 — 评估是否要回写 sql/ 保持单一来源:"
    echo "$extra_cols" | sed 's/^/    + /'
  fi
fi

# ---------- 结论 ----------
echo ""
if [[ -z "$missing_tables" && -z "$missing_cols" ]]; then
  echo "✅ 无漂移: live 库结构完整覆盖 sql/ 期望 (缺 0 表 / 0 列)"
  exit 0
else
  mt=$(echo "$missing_tables" | grep -c . || true)
  mc=$(echo "$missing_cols" | grep -c . || true)
  echo "⚠️  有漂移: 缺 ${mt} 表 / ${mc} 列。按上面清单逐条 SOURCE 对应 sql/ 文件补齐后重跑。"
  exit 1
fi
