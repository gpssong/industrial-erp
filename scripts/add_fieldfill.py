#!/usr/bin/env python3
"""
为 49 个 entity 补 @TableField(fill = FieldFill.INSERT) / INSERT_UPDATE 注解
+ import com.baomidou.mybatisplus.annotation.FieldFill

模式(所有 entity 一致):
  private Long createBy;
  private LocalDateTime createTime;
  private Long updateBy;
  private LocalDateTime updateTime;

替换为:
  @TableField(fill = FieldFill.INSERT)
  private Long createBy;
  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createTime;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private Long updateBy;
  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updateTime;

安全规则:
- 仅匹配 4 行连续 + 顺序 createBy / createTime / updateBy / updateTime
- 如果这 4 行已有 @TableField 注解(任意), 跳过该组 (避免重复注解)
- 已经 import FieldFill 的 entity 跳过 import
"""
import os
import re
import sys
from pathlib import Path

ROOT = Path("/Users/tongban/Documents/根据前端开发erp 2/erp-system/backend/src/main/java/com/industrial/erp/modules")
EXCLUDE = {".bak"}

# 模式: 4 行顺序,允许行间空白
PATTERN = re.compile(
    r"(\s*)"                                   # 行前空白
    r"private\s+Long\s+createBy\s*;\s*\n"
    r"\s*private\s+LocalDateTime\s+createTime\s*;\s*\n"
    r"\s*private\s+Long\s+updateBy\s*;\s*\n"
    r"\s*private\s+LocalDateTime\s+updateTime\s*;",
    re.MULTILINE,
)

def has_fieldfill_import(content):
    return "import com.baomidou.mybatisplus.annotation.FieldFill" in content

def process_file(path):
    src = path.read_text(encoding="utf-8")
    matches = list(PATTERN.finditer(src))
    if not matches:
        return False, "no match"

    # 检查是否已有注解 — 看每个匹配前的 5 个字符有没有 @TableField
    has_existing = False
    for m in matches:
        # 看 m.start() 之前的非空白是不是有 @TableField
        preceding = src[max(0, m.start()-200):m.start()]
        # 如果最近 200 字符里有 @TableField 在 match 的 createBy 行之前, 视为已有
        # 简单规则: match 范围前面紧邻的注释/空行后是不是有 @TableField
        # 用 grep: look at the lines immediately before the matched block
        before_block = src[:m.start()].rstrip()
        last_line_before = before_block.splitlines()[-1] if before_block.splitlines() else ""
        # 如果 @TableField 在上一行/上面几行(最多 2 行), 视为已有
        lines_before = before_block.splitlines()
        if len(lines_before) >= 1 and "@TableField" in lines_before[-1]:
            has_existing = True
            break
        # 或者 createBy/createTime 已经单独被 @TableField 注解 (可能中间有别的东西)

    if has_existing:
        return False, "already annotated"

    new_src = PATTERN.sub(
        lambda m: (
            f"{m.group(1)}@TableField(fill = FieldFill.INSERT)\n"
            f"{m.group(1)}private Long createBy;\n"
            f"{m.group(1)}@TableField(fill = FieldFill.INSERT)\n"
            f"{m.group(1)}private LocalDateTime createTime;\n"
            f"{m.group(1)}@TableField(fill = FieldFill.INSERT_UPDATE)\n"
            f"{m.group(1)}private Long updateBy;\n"
            f"{m.group(1)}@TableField(fill = FieldFill.INSERT_UPDATE)\n"
            f"{m.group(1)}private LocalDateTime updateTime;"
        ),
        src,
    )

    # 加 import (如果有 import com.baomidou.mybatisplus.annotation.TableField, 就在它后面插一行)
    if not has_fieldfill_import(new_src):
        if "import com.baomidou.mybatisplus.annotation.TableField;" in new_src:
            new_src = new_src.replace(
                "import com.baomidou.mybatisplus.annotation.TableField;",
                "import com.baomidou.mybatisplus.annotation.FieldFill;\nimport com.baomidou.mybatisplus.annotation.TableField;",
                1,
            )
        elif "import com.baomidou.mybatisplus.annotation.IdType;" in new_src:
            # 兜底: 在 IdType 后插
            new_src = new_src.replace(
                "import com.baomidou.mybatisplus.annotation.IdType;",
                "import com.baomidou.mybatisplus.annotation.FieldFill;\nimport com.baomidou.mybatisplus.annotation.IdType;",
                1,
            )

    path.write_text(new_src, encoding="utf-8")
    return True, "ok"


def main():
    targets = []
    for module_dir in ROOT.iterdir():
        if not module_dir.is_dir():
            continue
        entity_dir = module_dir / "entity"
        if not entity_dir.is_dir():
            continue
        for f in entity_dir.iterdir():
            if f.suffix != ".java" or f.name.endswith(".bak"):
                continue
            if f.name.endswith(".java.bak"):
                continue
            targets.append(f)

    changed = []
    skipped = []
    for f in sorted(targets):
        try:
            ok, reason = process_file(f)
            if ok:
                changed.append(f)
            else:
                skipped.append((f, reason))
        except Exception as e:
            skipped.append((f, f"err: {e}"))

    print(f"=== changed ({len(changed)}) ===")
    for f in changed:
        print(f"  {f.relative_to(ROOT.parents[2])}")
    print(f"\n=== skipped ({len(skipped)}) ===")
    for f, reason in skipped:
        print(f"  {f.relative_to(ROOT.parents[2])}  [{reason}]")


if __name__ == "__main__":
    main()