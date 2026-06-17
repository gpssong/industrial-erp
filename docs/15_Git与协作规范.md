# Git 与协作规范

> 适用人员: 全体开发 / 测试 / 运维
> 阅读前提: 已熟悉 git 基础(add / commit / push / pull)
> 本文档定义本项目的**分支策略、提交规范、Code Review、发布流程、冲突处理**,目标:**主分支随时可发布、Commit 历史可读、变更可追溯**。

## 一、分支策略(Git Flow 简化版)

### 1.1 分支类型

| 分支 | 命名 | 来源 | 归宿 | 用途 |
|---|---|---|---|---|
| **main** | `main` | — | — | 生产代码,**任何时候都可发布**,受保护 |
| **develop** | `develop` | main | — | 集成分支,日常开发合并目标 |
| **feature** | `feature/<module>-<desc>` | develop | develop | 新功能开发 |
| **bugfix** | `bugfix/<issue>-<desc>` | develop | develop | 普通 Bug 修复 |
| **hotfix** | `hotfix/<version>-<desc>` | main | main + develop | 紧急生产 Bug,**立刻上线** |
| **release** | `release/vX.Y.Z` | develop | main + develop | 发布前准备(版本号、Changelog) |
| **个人分支** | `dev-<name>` 或 `codex/<name>` | develop | develop | 长期个人开发分支(可选) |

### 1.2 流程图

```
                         main ─────────●─────────────► (tag v1.0.0)
                          │            │
                          │  hotfix    │  release
                          │            │
       feature/A ──┐     ╲            ╱
                   ├─► develop ──► release/v1.1.0 ──┐
       feature/B ──┘                                │
                                                    ▼
                                                  main (v1.1.0)
```

### 1.3 分支命名规范

✅ **推荐**:
```bash
feature/sales-add-credit
feature/transport-crud
bugfix/2163-stock-overflow
hotfix/1.0.1-login-token
release/v1.1.0
```

❌ **禁止**:
```bash
test
mybranch
john
feature/销售单新增信用额度   # 中文分支,部分 Git 服务器乱码
```

### 1.4 分支保护规则(GitHub / GitLab)

**main 分支**:
- ❌ 禁止直接 push
- ✅ 必须 PR + ≥ 1 Approve
- ✅ CI 全绿(单元测试、构建)
- ❌ 禁止 force push

**develop 分支**:
- ❌ 禁止直接 push(推荐 PR,但可放宽)
- ✅ CI 全绿

## 二、Commit 提交规范

### 2.1 Conventional Commits

**格式**:
```
<type>(<scope>): <subject>

<body>

<footer>
```

**Type(必填)**:

| Type | 含义 | 示例 |
|---|---|---|
| `feat` | 新功能 | `feat(sales): 新增销售出库信用额度校验` |
| `fix` | Bug 修复 | `fix(stock): 修复并发出库超卖问题` |
| `docs` | 文档 | `docs(readme): 更新部署说明` |
| `style` | 格式(无逻辑变更) | `style(backend): 统一缩进为 4 空格` |
| `refactor` | 重构(无新功能无 Bug) | `refactor(inventory): 抽取库存通用方法` |
| `perf` | 性能优化 | `perf(report): 销售汇总 SQL 优化, 1.2s → 80ms` |
| `test` | 测试 | `test(stock): 增加并发压测用例` |
| `chore` | 构建/工具/CI | `chore(ci): 升级 maven 至 3.9.6` |
| `revert` | 回滚 | `revert: feat(sales) 上一个 commit` |

**Scope(可选)**:
- 模块名:`sales` / `purchase` / `inventory` / `production` / `finance` / `system` / `base` / `report` / `print` / `transport`
- 端:`backend` / `pc-web` / `app` / `electron` / `sql` / `docs`
- 多个用 `,` 分隔:`feat(sales,report): 新增销售毛利报表`

**Subject(必填)**:
- 中文,**≤ 50 字**
- 不加句号
- 用动词开头:「新增 / 修复 / 优化 / 重构 / 调整 / 删除」
- 第一人称现在时:「新增」而非「新增了」

**Body(可选)**:
- 解释「为什么」而非「做了什么」(代码能看出做了什么)
- 多行,每行 ≤ 72 字符
- 与 Subject 空一行

**Footer(可选)**:
- `Closes #123` 关闭 Issue
- `BREAKING CHANGE: 库存 API 返回结构变更,需重新发布前端`

### 2.2 完整示例

```
feat(sales): 新增销售出库单信用额度校验

业务背景: 客户超额度开单导致坏账,需在开单时强制校验。
实现:
  1. SalDeliveryService.createDelivery() 增加信用检查
  2. 超出额度抛 BizException("信用额度不足")
  3. 单元测试覆盖 3 个场景

Closes #2156
```

```
fix(stock): 修复并发出库超卖问题

原代码只用 Redis 分布式锁,Redis 主从切换时会丢锁。
改进: 增加 MySQL 行锁 SELECT ... FOR UPDATE 双保险。
重现: StockConcurrencyTest (50 并发)。

BREAKING CHANGE: StockService.inStock/outStock 入参新增 supplierId/customerId,需要上游传值。
```

### 2.3 Commitizen 工具(推荐)

```bash
# 安装
npm i -g commitizen cz-conventional-changelog
echo '{ "path": "cz-conventional-changelog" }' > ~/.czrc

# 交互式生成
cd erp-system
git cz
# ? Select the type of change: feat
# ? What is the scope: sales
# ? Write a short description: 新增信用额度校验
# ? Write a longer description: ...
# ? Are there any breaking changes? No
# ? Does this change affect any open issues? #2156
```

### 2.4 Commit 原子性原则

✅ **推荐**:
- 一个 Commit 只做一件事
- 一个功能拆 3~5 个 Commit:`feat(sales): add credit check service` → `feat(sales): add credit check api` → `test(sales): add credit check test` → `docs(api): update sales api doc`

❌ **禁止**:
- 一个 Commit 改 50 个文件,带 5 个不相关修改
- 提交 100MB 编译产物
- 提交 `application-local.yml` 包含真实密码

## 三、PR / Code Review

### 3.1 PR 模板

**`.github/pull_request_template.md`**:

```markdown
## 变更类型
- [ ] 新功能
- [ ] Bug 修复
- [ ] 重构
- [ ] 性能优化
- [ ] 文档

## 关联 Issue
Closes #

## 变更内容
<!-- 简述做了什么、为什么 -->

## 测试
- [ ] 单元测试已加
- [ ] Knife4j 接口验证
- [ ] 前端 Chrome / 移动端真机验证

## 截图 / 录屏
<!-- UI 变更必填 -->

## Checklist
- [ ] 代码符合本项目规范
- [ ] 自我 Code Review 一遍
- [ ] 无 Console / 日志残留
- [ ] 数据库变更已加 Flyway / Liquibase
- [ ] 文档已同步更新
- [ ] 不包含敏感信息
```

### 3.2 Review 检查清单

**Reviewer 从 5 个维度看**:

#### 1. 正确性
- [ ] 业务逻辑是否与需求一致?
- [ ] 边界条件:空值、负数、超长、并发?
- [ ] 异常路径是否处理?
- [ ] 状态机是否完整(草稿→审核→完成的逆向禁用)?

#### 2. 安全性
- [ ] SQL 注入(MP Wrapper 已防,XML 拼接 `${}` 需审查)?
- [ ] 越权(横向/纵向)?`@SaCheckPermission`?
- [ ] 敏感信息打印?日志脱敏?
- [ ] 前端 XSS(`v-html`、innerHTML)?

#### 3. 性能
- [ ] 是否有 N+1 查询?
- [ ] 大列表是否分页?
- [ ] 是否走索引?`EXPLAIN`?
- [ ] 是否能加缓存?

#### 4. 可维护性
- [ ] 命名是否清晰(避免 a/b/c)?
- [ ] 是否重复造轮子(已有 util 未复用)?
- [ ] 注释是否必要(why 而非 what)?
- [ ] 公共方法是否加 Javadoc?

#### 5. 测试
- [ ] 是否有单元测试?
- [ ] 测试覆盖核心分支?
- [ ] 是否包含异常用例?

### 3.3 Review 反馈礼仪

✅ **好的反馈**:
```
这里考虑边界情况: 当 details 为空数组时, freightAmount 是 0,
但用户可能误传 null 导致 NPE,建议加 `Optional.ofNullable`。
```

```
小建议: 这个工具方法 utils/DateUtil 里已经有 parseLocalDate,
这里直接复用即可,不必再写一遍。
```

❌ **不好的反馈**:
```
代码写得太烂了
这个不对
重写吧
```

**所有反馈用「提问 / 建议」语气,避免「命令 / 否定」**。

### 3.4 Review 流程

```
开发者  ──PR──►  CI  ──►  Reviewer  ──►  Approve  ──►  合并
                       │                  │
                       ▼                  ▼
                    失败:修复          评论:讨论 → 修改
```

**规则**:
- 1 个 Approve + CI 全绿 = 可合并
- 复杂 PR 需 2 个 Approve(架构师 / 模块 owner)
- 作者不能 Review 自己的 PR
- 24 小时未响应,适度 @催促

## 四、发布流程

### 4.1 版本号规范(SemVer)

`v<major>.<minor>.<patch>[-<pre>]`

- **major**: 不兼容 API 变更
- **minor**: 新增功能(向下兼容)
- **patch**: Bug 修复(向下兼容)
- **pre**:`alpha` / `beta` / `rc`

示例:
- `v1.0.0` - 首次正式发布
- `v1.0.1` - 修复 Bug
- `v1.1.0` - 新增运输模块
- `v2.0.0-beta.1` - 重大重构 beta 版

### 4.2 发布步骤

```bash
# 1. 从 develop 切出 release 分支
git checkout develop
git pull
git checkout -b release/v1.1.0

# 2. 更新版本号 & Changelog
# 改 pom.xml: <version>1.1.0</version>
# 改 package.json: "version": "1.1.0"
# 写 CHANGELOG.md

git add -A
git commit -m "chore(release): v1.1.0"

# 3. 合并到 main + 打 tag
git checkout main
git merge --no-ff release/v1.1.0
git tag -a v1.1.0 -m "v1.1.0 运输模块发布"
git push origin main --tags

# 4. 同步回 develop
git checkout develop
git merge --no-ff release/v1.1.0
git push origin develop

# 5. 删除 release 分支
git branch -d release/v1.1.0
```

### 4.3 Hotfix 紧急修复

```bash
# 1. 从 main 切
git checkout main
git checkout -b hotfix/1.0.1-login-token

# 2. 修复
git commit -m "fix(auth): 修复 token 过期时间不准确"

# 3. 合并到 main + develop
git checkout main
git merge --no-ff hotfix/1.0.1-login-token
git tag -a v1.0.1 -m "v1.0.1 紧急修复"
git push origin main --tags

git checkout develop
git merge --no-ff hotfix/1.0.1-login-token
git push origin develop

git branch -d hotfix/1.0.1-login-token
```

## 五、.gitignore 规范

项目根 `.gitignore` 必须包含:

```gitignore
# IDE
.idea/
.vscode/
*.iml
*.iws
*.ipr
.project
.classpath
.settings/

# Java
target/
build/
*.class
*.jar
*.war
hs_err_pid*.log
replay_pid*.log

# Node
node_modules/
dist/
.vite/
.cache/
.pnpm-store/

# Logs / OS
*.log
logs/
.DS_Store
Thumbs.db
ehcache/

# Env / 凭据
.env
.env.local
application-local.yml
application-prod.yml
*.pem
*.key
*.p12
```

**注意**:`application-prod.yml` 不入仓,**生产配置在服务器 `/etc/industrial-erp/config/` 维护**。

## 六、冲突处理

### 6.1 Rebase vs Merge

| 场景 | 推荐 |
|---|---|
| 拉取远程 develop 到本地 feature | `git pull --rebase`(线性历史) |
| 合并 feature 到 develop | `git merge --no-ff`(保留分支信息) |
| 合并 release/hotfix 到 main | `git merge --no-ff` |
| 清理本地 commit | `git rebase -i HEAD~3` |

### 6.2 解决冲突步骤

```bash
# 1. rebase 时冲突
git rebase develop
# CONFLICT (...) in SalDeliveryService.java

# 2. 打开文件,搜索 <<<<<<<
vim SalDeliveryService.java
# 保留正确代码,删除 <<<<<<< ======= >>>>>>>

# 3. 标记为已解决
git add SalDeliveryService.java
git rebase --continue

# 4. 中途放弃
git rebase --abort
```

### 6.3 危险操作

❌ **禁止**:
- `git push --force` 到 main / develop(用 `--force-with-lease` 仍慎用)
- `git reset --hard` 已 push 的 commit
- 在生产分支上 `git commit --amend` 改历史

✅ **安全回滚**:
```bash
# 撤销某次 commit(产生新 commit,不改历史)
git revert <commit-id>
git push
```

## 七、Commit 历史书写规范示例

一个清晰的功能 PR 历史长这样:

```
* feat(sales): 新增销售开单信用额度校验
* test(sales): 增加信用额度边界测试
* docs(api): 更新销售出库 API 文档
* chore(sales): 移除废弃字段
```

而不是:

```
* update
* fix
* 改了改
* 改完了
* 真的改完了
* 这次真的改完了
```

**用 `git log --oneline --graph` 看到的历史应该像「故事」一样可读**。

## 八、Git Hooks(可选)

`.git/hooks/pre-commit`(本地提交前检查):

```bash
#!/bin/bash
# 防止大文件
MAX_SIZE=10485760  # 10MB
git diff --cached --name-only | while read f; do
  if [ -f "$f" ]; then
    size=$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f")
    if [ "$size" -gt "$MAX_SIZE" ]; then
      echo "❌ 文件 $f 超过 10MB，禁止提交"
      exit 1
    fi
  fi
done

# 防止提交密钥
if git diff --cached | grep -E "(password|secret|token)\s*[:=]\s*['\"][^'\"]{8,}"; then
  echo "❌ 检测到可能的密钥，禁止提交"
  exit 1
fi

# 后端:运行单测
if git diff --cached --name-only | grep -q "backend/src/main"; then
  echo "Running backend unit tests..."
  cd backend && mvn -q test -Dtest=*Test 2>&1 | tail -20
  if [ ${PIPESTATUS[0]} -ne 0 ]; then
    echo "❌ 单测失败,禁止提交"
    exit 1
  fi
fi
```

启用:
```bash
chmod +x .git/hooks/pre-commit
```

## 九、协作工具链

| 工具 | 用途 |
|---|---|
| **GitHub / GitLab** | 代码托管 + PR + Issue |
| **Conventional Commits** | Commit 规范 |
| **Commitizen** | 交互式生成 Commit |
| **semantic-release** | 自动化版本号 + Changelog |
| **husky + lint-staged** | 提交前 lint |
| **SonarQube** | 代码质量平台 |
| **Codacy / CodeClimate** | 自动 Code Review 建议 |
| **GitHub Actions / GitLab CI** | 自动化流水线 |

## 十、协作速查卡

```bash
# 1. 拉取最新 develop
git checkout develop && git pull --rebase

# 2. 切功能分支
git checkout -b feature/sales-credit

# 3. 编码 + 提交(多次)
git add .
git cz                    # 推荐
# 或
git commit -m "feat(sales): add credit check service"

# 4. 推送 + 提 PR
git push -u origin feature/sales-credit
# GitHub 上提 PR: feature/sales-credit → develop

# 5. Review 通过 + 合并后
git checkout develop
git pull --rebase
git branch -d feature/sales-credit
git remote prune origin

# 6. 发布(由 Release Manager 执行)
git checkout develop
git checkout -b release/v1.1.0
# 改版本号、Changelog
git commit -m "chore(release): v1.1.0"
git checkout main && git merge --no-ff release/v1.1.0
git tag -a v1.1.0 -m "v1.1.0"
git push origin main --tags
git checkout develop && git merge --no-ff release/v1.1.0
git push origin develop
```

> **好的 Git 习惯 = 清晰的历史 + 高效的协作 + 安全的回滚**。
