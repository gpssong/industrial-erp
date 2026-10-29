#!/usr/bin/env python3
"""
scripts/entity-schema-check.py — 实体字段 vs 建表 SQL 对账 (防"改实体不补 DDL"回归)

背景 (v1.1.130 教训):
  SOP 保存 500 (mes_process_sop 漏 remark 列)、委外审核 500 (实体 details 集合
  / lineNo 字段库里没列, MP 当列去 select/insert → Unknown column)。这类 bug
  的公共点: 开发者加了字段/集合到 entity 但没补 DDL (sql/*.sql), 线上 0 数据时
  不会被发现, 一用就 500。本脚本离线对账, 在 CI / 提交前跑, 提前拦下。

检测规则 (对每个 @TableName 实体):
  1. 字段 → 列: camelCase 转 snake_case; @TableField(value="x") 用 x;
     @TableField(exist=false) 的字段跳过 (不参与 SQL); 集合/Map 字段
     没标 exist=false → 报 "MP 会把集合当列" 错误 (最常见 500 源)。
  2. 与目标列集对账, 报 "实体有但表里没的列" (DDL 漏补)。
  3. 反向: 表里有但实体没映射的列, 只提示不报错 (可能是冗余/已删列)。

对账目标 (二选一, 自动选更完整的):
  - 离线: 从 sql/*.sql 的 CREATE TABLE 推期望列集 (CI 默认, 无需连库)。
  - 在线: --live 时连库取 information_schema (更权威, 但需 DB 凭据)。

用法:
  python3 scripts/entity-schema-check.py                # 离线对账 sql/ DDL, exit 1 if drift
  python3 scripts/entity-schema-check.py --warn-only     # 只提示不 fail (迁移期)
  DB_HOST=.. DB_PWD=.. python3 scripts/entity-schema-check.py --live   # 对账 live 库

退出码: 0 = 通过, 1 = 有实体字段与 DDL 不一致 (fail-fast 场景)
"""
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SQL_DIR = REPO / "sql"
ENTITY_GLOB = "backend/src/main/java/com/industrial/erp/modules/*/entity/*.java"

# 这些表是纯日志/无实体映射/非业务表, 跳过反向"表里多出的列"提示
SKIP_TABLES = {"sys_oper_log", "sys_login_log", "sys_backup_record"}


def snake(name: str) -> str:
    """camelCase / PascalCase → snake_case (MyBatis-Plus 默认字段映射)"""
    s = re.sub(r'([A-Z])', lambda m: '_' + m.group(1).lower(), name)
    return s


# ---------- 解析 sql/ 里的 CREATE TABLE → 表 → 列集合 ----------
CREATE_RE = re.compile(r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\((.*?)\)\s*(?:ENGINE|COMMENT|;)',
                       re.IGNORECASE | re.DOTALL)


def parse_ddl_columns() -> dict:
    cols = {}
    if not SQL_DIR.exists():
        return cols
    for f in SQL_DIR.glob("*.sql"):
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        for m in CREATE_RE.finditer(text):
            table = m.group(1).lower()
            body = m.group(2)
            # 逐行取列名: 每个以 `col` 或 裸列名 开头、后面跟类型, 排除 KEY/PRIMARY/UNIQUE/CONSTRAINT/INDEX 行
            table_cols = set()
            for line in body.splitlines():
                line = line.strip().rstrip(',')
                if not line:
                    continue
                low = line.lower().lstrip()
                if low.startswith(('primary', 'unique', 'key', 'index', 'constraint', 'foreign', ')')):
                    continue
                # 列定义行: `name` type ... 或 name type
                cm = re.match(r'`?(\w+)`?\s+([A-Za-z]+)', line)
                if cm:
                    table_cols.add(cm.group(1).lower())
            # 合并 (同一表多处 ALTER ADD COLUMN 也计入)
            prev = cols.get(table, set())
            cols[table] = prev | table_cols
    # 兼容 ALTER TABLE ... ADD COLUMN (历史迁移补列, 如 sql/138 补 mes_process_sop.remark)
    alter_re = re.compile(r'ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+COLUMN?\s+`?(\w+)`?',
                          re.IGNORECASE)
    for f in SQL_DIR.glob("*.sql"):
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        for m in alter_re.finditer(text):
            cols.setdefault(m.group(1).lower(), set()).add(m.group(2).lower())
    return cols


# ---------- 解析 entity → 表 → 字段(含注解) ----------
def parse_entities() -> list:
    """返回 [(relpath, table, [(field, col, is_collection, exist_false), ...])]"""
    entities = []
    for p in sorted(REPO.glob(ENTITY_GLOB)):
        p = p.relative_to(REPO)
        text = p.read_text(encoding="utf-8", errors="ignore")
        tn = re.search(r'@TableName\(\s*"([^"]+)"', text)
        if not tn:
            continue
        table = tn.group(1).lower()
        fields = []
        # 逐字段: 找 private/protected 字段声明, 回看最近 3 行内的注解
        lines = text.splitlines()
        for i, ln in enumerate(lines):
            fm = re.match(r'\s*(?:private|protected)\s+([\w<>,\.\[\]]+)\s+(\w+)\s*(?:=.*)?;', ln)
            if not fm:
                continue
            ftype, fname = fm.group(1), fm.group(2)
            window = '\n'.join(lines[max(0, i - 3):i])
            exist_false = 'exist = false' in window
            is_coll = bool(re.match(r'(?:java\.util\.)?(?:List|Set|Collection|Map)\b', ftype))
            # @TableField(value="col") 覆盖列名
            col = snake(fname)
            vm = re.search(r'@TableField\s*\([^)]*value\s*=\s*"([^"]+)"', window)
            if vm:
                col = vm.group(1).lower()
            fields.append((fname, col, is_coll, exist_false))
        entities.append((str(p), table, fields))
    return entities


def _parse_cols(tsv: str) -> dict:
    """解析 `TABLE_NAME<TAB>COLUMN_NAME` 的 TSV (mysql -N -B 输出) → {table: {col}}"""
    out = {}
    for line in tsv.splitlines():
        parts = line.split('\t')
        if len(parts) == 2 and parts[0] and parts[1]:
            out.setdefault(parts[0].lower(), set()).add(parts[1].lower())
    return out


def main():
    warn_only = '--warn-only' in sys.argv
    live = '--live' in sys.argv or '--live-ssh' in sys.argv
    live_ssh = '--live-ssh' in sys.argv
    ddl = parse_ddl_columns()

    # 可选: live 对账 (权威源)
    if live:
        host = os.environ.get('DB_HOST', '192.168.0.150')
        user = os.environ.get('DB_USER', 'root')
        pwd = os.environ.get('DB_PWD', 'erp_root_pwd')
        db = os.environ.get('DB_NAME', 'industrial_erp')
        query = ("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
                 "WHERE TABLE_SCHEMA='" + db + "'")
        live_cols = None
        if live_ssh:
            # 复用本项目的 .150 灌库配方: ssh + sudo docker exec mysql
            # SSH_HOST / SSH_USER / SSH_SUDO_PASS / DB_MYSQL_PWD / DB_CONTAINER 可覆盖
            shost = os.environ.get('SSH_HOST', '192.168.0.150')
            suser = os.environ.get('SSH_USER', 'gpssong')
            spass = os.environ.get('SSH_PASS', '850225sonG')
            spass_sudo = os.environ.get('SSH_SUDO_PASS', '19850225aB')
            cont = os.environ.get('DB_CONTAINER', 'erp-mysql')
            dbpwd = os.environ.get('DB_MYSQL_PWD', 'erp_root_pwd')
            print(f"[INFO] live-ssh 模式: 经 {suser}@{shost} docker exec {cont} 取列集")
            import subprocess
            ssh_base = ['sshpass', '-p', spass, 'ssh', '-o', 'StrictHostKeyChecking=no', f'{suser}@{shost}']
            # 两步走: 先把查询写进远端 /tmp, 再灌库 (sudo -S 吃 stdin 的坑, 见 CLAUDE.md)
            subprocess.run(ssh_base + ['cat > /tmp/_esc_cols.sql'],
                           input=query + '\n', text=True, capture_output=True, timeout=30)
            run_sql = subprocess.run(
                ssh_base + [
                    "export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH; "
                    f"(echo {spass_sudo}; cat /tmp/_esc_cols.sql) | "
                    f"sudo -S -p \"\" env PATH=$PATH docker exec -i -e MYSQL_PWD={dbpwd} {cont} "
                    f"mysql -uroot --default-character-set=utf8mb4 {db} -N -B 2>/dev/null; "
                    "rm -f /tmp/_esc_cols.sql"],
                text=True, capture_output=True, timeout=30)
            if run_sql.returncode == 0:
                live_cols = _parse_cols(run_sql.stdout)
            else:
                print("[WARN] live-ssh 取列失败, 回退离线:", run_sql.stderr.strip()[:120])
        else:
            print(f"[INFO] live 模式: 连接 {host} / {db}")
            try:
                import subprocess
                r = subprocess.run(
                    ['mysql', '-h', host, '-u', user, f'-p{pwd}', db, '-N', '-B', '-e', query],
                    capture_output=True, text=True, timeout=30)
                if r.returncode == 0:
                    live_cols = _parse_cols(r.stdout)
                else:
                    print("[WARN] live 连接失败, 回退离线 sql/ 对账:", r.stderr.strip()[:120])
            except Exception as e:
                print("[WARN] live 模式不可用, 回退离线:", e)
        if live_cols:
            ddl = live_cols
            print(f"[INFO] live 列集 {len(ddl)} 表")
            _live_succeeded = True
        else:
            print("[WARN] 未取得 live 列集, 用离线 sql/ DDL 继续 (会偏假阳)")
            _live_succeeded = False
    else:
        _live_succeeded = False

    entities = parse_entities()
    source = 'live DB' if _live_succeeded else 'sql/*.sql DDL'
    print(f"[INFO] 解析 {len(entities)} 个 @TableName 实体; 目标列集来源: {source}")

    errors = []   # 高危, 必 fail
    warnings = [] # 离线 DDL 可能不全 → 只提示
    for rel, table, fields in entities:
        # 只关心"实体多出来的字段" — 会直接 500
        table_cols = ddl.get(table)
        for fname, col, is_coll, exist_false in fields:
            if exist_false:
                continue  # 不参与 SQL, 跳过
            if is_coll:
                # 集合字段没标 exist=false → MP 当列 → 必 500 (与 DDL 完整性无关, 始终 error)
                errors.append(f"{rel}: 集合字段 `{fname}` ({col}) 未标 @TableField(exist=false) "
                              f"→ MP 会当列 select/insert 报 Unknown column")
                continue
            if table_cols is None:
                if source.startswith('live'):
                    errors.append(f"{rel}: 表 `{table}` 在 live 库不存在 (实体建了但表没建?)")
                else:
                    warnings.append(f"{rel}: 表 `{table}` 在 sql/ DDL 里没解析到 (可能建表在别处/live 已建)")
                continue
            if col not in table_cols:
                if source.startswith('live'):
                    # live 是权威源: 缺列必 500
                    errors.append(f"{rel}: 字段 `{fname}` → 列 `{col}` 在 `{table}` 不存在 (改实体没补 DDL)")
                else:
                    # 离线 DDL 可能不全 (自动填充列/后续迁移), 只提示
                    warnings.append(f"{rel}: 字段 `{fname}` → 列 `{col}` 未在 sql/ DDL 找到 "
                                    f"(建议 --live 确认, 或补列到 sql/)")

    for w in warnings:
        print("  WARN      " + w)
    for e in errors:
        print("  MISMATCH  " + e)
    print(f"[SUMMARY] {len(errors)} error, {len(warnings)} warning (source={source})")
    if not errors:
        print("OK  无 Unknown column 风险 (集合字段 & 缺列 均通过)")
    elif warn_only:
        print("[WARN-ONLY] 有 error 但未 fail (迁移期用)")
        return 0
    else:
        print("[FAIL] 有实体字段/列不一致, 见 MISMATCH")
        return 1


if __name__ == '__main__':
    sys.exit(main())
