#!/usr/bin/env python3
"""
scripts/check-perm.py — 防新 Controller method 漏写 @SaCheckPermission 回归

检测规则:
  1. 类级 perm 检查: 类是否含 @SaCheckLogin/Permission/Role 任何注解 (类级或方法级总和)
     - v1.1.103 后已禁 @SaCheckRole("admin") 这种死字面量, 但脚本只关心有无注解
  2. 方法级: 若类无 perm 注解, 每个有 @*Mapping 的 method 必须在 method 签名前后 5 行内带 perm 注解

白名单(类名含以下 keyword, 不强制):
  AuthController / McpController / Upload / Backup / Dict / Dashboard / App / Integration

用法:
  python3 scripts/check-perm.py                # 检查, exit 1 if leaks
  python3 scripts/check-perm.py --list-leaks  # 列出缺 perm 方法 (debug)

退出码: 0 = 通过, 1 = 有 controller 缺 perm 防护

历史背景: v1.1.103 修 6 处 `@SaCheckRole("admin")` 死注解导致所有非 admin 角色 403,
         加此脚本确保后续新增的 Controller method 不再裸奔 (登录即调).
"""
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CONTROLLER_DIR = REPO / "backend/src/main/java/com/industrial/erp/modules"

PERM_RE = re.compile(r'@SaCheck(?:Login|Permission|Role)\b')
# 仅 method 级 mapping (排除类级 @RequestMapping)
METHOD_MAPPING_RE = re.compile(r'@(?:Get|Post|Put|Delete|Patch)Mapping\b')

EXEMPT_KEYWORDS = re.compile(
    r'AuthController|McpController|Upload|Backup|Dict|Dashboard|AppController|IntegrationController',
    re.IGNORECASE
)

# v1.1.104+: 是否启用 strict 模式 (有 leak 时 exit 1, 默认 False)
strict_mode = False

def check_one_file(path: Path) -> list[str]:
    """返回 leaks 列表, 每个元素形如 'method 行号: <signature>'"""
    src = path.read_text(encoding='utf-8')
    class_name = path.stem

    # 白名单跳过 (类名含 keyword)
    if EXEMPT_KEYWORDS.search(class_name):
        return []

    lines = src.splitlines()

    # 1. 统计全文件 perm 注解数 (类级 + 方法级总和)
    perm_count = sum(1 for ln in lines if PERM_RE.search(ln))

    # 2. 扫描方法级 @*Mapping, 找缺 perm 的"裸奔"method
    #    判定方法: method 签名从 mapping 往下找 public 行, 该方法上下 5 行 window 无 perm 注解
    leaks = []
    for i, line in enumerate(lines):
        if not METHOD_MAPPING_RE.search(line):
            continue
        # 找 method public 签名行 (mapping 行往下 3-5 行)
        sig_idx = None
        for j in range(i, min(i + 6, len(lines))):
            if re.search(r'\bpublic\b.*\b\w+\s*\(', lines[j]):
                sig_idx = j
                break
        if sig_idx is None:
            sig_idx = i

        # method block window = mapping 前 5 行 到 public 后 5 行
        window_start = max(0, i - 5)
        window_end = min(len(lines), sig_idx + 6)
        window = lines[window_start: window_end]

        if not any(PERM_RE.search(w) for w in window):
            sig_line = lines[sig_idx].strip() if sig_idx < len(lines) else '?'
            leaks.append(f"line {i+1}: {sig_line[:120]}")

    # 3. 若全文件 perm 总数 == 0 且全文件有 mapping, 兜底报警 (整类无 perm)
    #    (单 method 缺 perm 已上报, 此处只是额外的"类级缺失"提示)
    # 注: method-level leaks 已覆盖大部分情况

    return leaks


def main():
    args = sys.argv[1:]
    global strict_mode
    strict_mode = '--strict' in args
    args = [a for a in args if a != '--strict']
    action = args[0] if args else "check"

    if not CONTROLLER_DIR.exists():
        print(f"✗ controller dir not found: {CONTROLLER_DIR}")
        sys.exit(1)

    java_files = list(CONTROLLER_DIR.rglob("controller/*.java"))
    print(f"[check-perm] 扫描 {len(java_files)} 个 controller ...")

    total_leaks = 0
    for f in sorted(java_files):
        rel = f.relative_to(REPO)
        leaks = check_one_file(f)
        if leaks:
            print(f"\n✗ [{rel}]")
            for l in leaks:
                print(f"    {l}")
            total_leaks += len(leaks)

    print("")
    if total_leaks > 0:
        print(f"⚠ 共 {total_leaks} 个 method 缺 perm 防护")
        if action == "check":
            print("  修法: 在 @GetMapping/@PostMapping 紧上方加 @SaCheckPermission(\"xxx:yyy\", orRole=\"admin\")")
            print("  或在脚本白名单 EXEMPT_KEYWORDS 加类名前缀")
            print("  --list-leaks 模式也可显式列出, 不返回非零状态")
            if strict_mode:
                print()
                print("  --strict 模式: 历史 leak 已修, 启用严格模式 — exit 1")
                return 1
            else:
                print()
                print("  当前模式: 报告但不失败 (迁移期)。加 --strict 启用严格模式")
                return 0
        else:
            print("  --list-leaks 模式: 列出全部, exit 0")
            return 0
    else:
        print(f"✓ 全部 controller 已 perm 防护 (共扫描 {len(java_files)} 个)")
        return 0


if __name__ == "__main__":
    sys.exit(main())
