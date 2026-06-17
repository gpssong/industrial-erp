# 常见问题 FAQ

> 适用人员: 全体开发 / 实施 / 运维 / 客户
> 本文档汇总 **部署 / 启动 / 性能 / 业务 / 异常** 五大类常见问题,90% 的问题能在这里找到答案。
> 问题持续维护中,如未覆盖请提 Issue。

## 一、部署类

### Q1.1:Docker 启动后,后端连不上 MySQL?

**A**:`docker-compose.yml` 启动顺序问题。

```bash
# ❌ 错误:两个容器同时启动,后端先于 MySQL 就绪
docker compose up -d

# ✅ 正确:先 MySQL,等就绪再起后端
docker compose up -d mysql
sleep 30  # 等 30 秒
docker compose up -d backend
# 或加 healthcheck(已在 docker-compose.yml 配好)
docker compose up -d
```

**检查**:
```bash
docker logs industrial-erp-mysql
docker logs industrial-erp-backend
# 看是否在重试连接 com.mysql.cj.jdbc.exceptions.CommunicationsException
```

### Q1.2:端口 8080 / 3000 / 6379 被占用

**A**:
```bash
# macOS / Linux
lsof -i :8080
# kill 占用的进程
kill -9 <PID>

# 或改 docker-compose.yml 端口映射
ports:
  - "8081:8080"  # 宿主机:容器
```

### Q1.3:前端访问后端报 CORS 跨域

**A**:
- 后端 `SaTokenConfig` 已配 `SaInterceptor` 跨域,确保启动加载。
- Nginx 反代:必须加 `proxy_set_header Host $host;` 与 `proxy_pass http://backend:8080;`
- 开发期 Vite proxy 配置正确(见 11_前端开发指南)

### Q1.4:上传文件 413 Payload Too Large

**A**:
```nginx
# nginx.conf
client_max_body_size 100M;
```

```yaml
# application.yml
spring:
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 100MB
```

### Q1.5:HTTPS 配置?

**A**:推荐 Nginx 终止 HTTPS,后端仍 HTTP。

```nginx
server {
  listen 443 ssl;
  server_name erp.example.com;

  ssl_certificate     /etc/nginx/ssl/erp.example.com.crt;
  ssl_certificate_key /etc/nginx/ssl/erp.example.com.key;

  location /api/ {
    proxy_pass http://backend:8080;
  }
  location / {
    root /var/www/erp-pc;
    try_files $uri $uri/ /index.html;
  }
}
```

证书用 Let's Encrypt 免费签发:
```bash
certbot certonly --nginx -d erp.example.com
```

### Q1.6:打包后端 jar 太大(> 200MB)?

**A**:Spring Boot 3 默认包含所有依赖。用 `spring-boot-maven-plugin` 拆出 layers。

```xml
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <configuration>
    <layers>
      <enabled>true</enabled>
    </layers>
  </configuration>
</plugin>
```

```bash
java -Djarmode=layertools -jar app.jar extract
```

## 二、启动类

### Q2.1:启动报错 `Public Key Retrieval is not allowed`

**A**:MySQL 8.0 默认 `caching_sha2_password` 认证,需要显式允许。

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/erp?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai
```

### Q2.2:启动报错 `Bean 'xxx' could not be found`

**A**:常见原因:
- `@MapperScan` 漏配(检查 `IndustrialErpApplication.java`)
- `@Service` / `@Component` 漏注解
- 循环依赖:用 `@Lazy` 或重构

### Q2.3:启动慢,等了 30 秒还没好

**A**:
```bash
# 看启动日志
java -jar app.jar --debug

# 或加 JVM 参数打印启动时间
java -verbose:class -jar app.jar | head
```

**优化**:
- 减少 `@ComponentScan` 范围
- 关闭不需要的 `autoConfiguration`:`@SpringBootApplication(exclude = {...})`
- 关闭 Sa-Token 自动构建:`sa-token.active-timeout: 30min` 等

### Q2.4:登录后立即跳回登录页

**A**:Cookie / Header 未携带 Token。
- 前端 Axios 拦截器:统一从 `localStorage.token` 取,加到 `Authorization: Bearer xxx`
- 后端 Sa-Token 配 `token-name: Authorization`(`Authorization: Bearer ` 前缀)
- 同源 / CORS

### Q2.5:Redis 连不上

**A**:
```bash
# 检查 Redis
redis-cli -h localhost -p 6379 ping
# PONG 表示 OK

# 检查密码
redis-cli -h localhost -p 6379 -a yourpass ping

# 防火墙
sudo ufw allow 6379
```

```yaml
spring:
  redis:
    host: localhost
    port: 6379
    password: yourpass
    timeout: 5s
    lettuce:
      pool:
        max-active: 50
        max-idle: 20
        min-idle: 5
```

## 三、性能类

### Q3.1:列表查询慢(> 3s)

**排查 5 步**:
1. **看 SQL**:`grep "Slow SQL" app.log` 或 p6spy
2. **EXPLAIN**:`EXPLAIN SELECT * FROM inv_stock WHERE product_id = 1;`
3. **加索引**:
   ```sql
   ALTER TABLE inv_stock ADD INDEX idx_product (product_id, batch_no, deleted);
   ```
4. **减少 JOIN**:冗余字段避免多表关联
5. **分页优化**:大表深分页用「**游标分页**」替代 `LIMIT 1000000, 20`:

```sql
-- ❌ 慢
SELECT * FROM inv_stock ORDER BY id LIMIT 1000000, 20;

-- ✅ 快(前提:有 id 索引)
SELECT * FROM inv_stock WHERE id > 1000000 ORDER BY id LIMIT 20;
```

### Q3.2:库存并发超卖

**A**:项目已用 **Redis 分布式锁 + MySQL 行锁** 双保险,理论上不会超卖。
如发生,检查:
- `StockService.inStock` / `outStock` 是否有 `@Transactional`
- 是否绕过 Service 直接 `UPDATE inv_stock`(严禁)
- Redis 主从切换:配置 `min-slaves-to-write 1`

### Q3.3:报表导出 Excel 内存溢出

**A**:**禁止** `List<Record> list = jdbc.queryForList(...); wb.write(list)` 全内存。

✅ **用 EasyExcel 流式导出**:
```java
ExcelWriter writer = EasyExcel.write(response.getOutputStream(), SalDeliveryVO.class).build();
WriteSheet sheet = EasyExcel.writerSheet(0, "销售出库").build();
SheetDataProcessor processor = new SheetDataProcessor(salDeliveryMapper, query);
writer.write(processor, sheet);
writer.finish();
```

`SheetDataProcessor` 继承 `AnalysisEventListener`,每 100 行刷一次内存。

### Q3.4:接口 P99 > 1s 怎么优化?

| 优化方向 | 方法 |
|---|---|
| 慢 SQL | 加索引 / 重写 SQL |
| N+1 | 批量 `IN` 查询 / `JOIN` |
| 大字段 | 拆分大表 / TEXT 单独存 |
| 序列化 | 用 `Protobuf` 替代 `JSON`(极端场景) |
| 缓存 | Redis 缓存热点数据(注意失效) |
| 异步 | `@Async` 处理非关键路径 |
| 限流 | Sentinel / Resilience4j 防止过载 |

## 四、业务类

### Q4.1:库存出现负数?

**A**:**绝对禁止**。原因排查:
- 是否绕过 `StockService` 直接 `UPDATE`?
- 业务代码是否忘了 `try-catch` 抛 `BizException`?
- 并发场景:用 `StockServiceTest.testOutStock_StrictlyProhibitNegative` 验证

修复:
```sql
-- 加约束(数据库兜底,应用层是第一道)
ALTER TABLE inv_stock ADD CONSTRAINT chk_qty CHECK (qty >= 0);
```

### Q4.2:成本计算有误?

**A**:本项目用**移动加权平均法**:
```
新成本 = (旧库存金额 + 本次入库金额) / (旧库存数量 + 本次入库数量)
```

常见错误:
- 入库金额为 0:确认 `price` 字段
- 退货未冲减成本:检查 `purchaseReturnService`
- 调拨未改成本:调拨不改成本(成本随仓库走),但**改归属仓库**

### Q4.3:毛利计算?

```sql
-- 销售毛利 = 收入 - 成本
SELECT
  sal.bill_no,
  sal.total_amount - (sald.qty * inv.avg_cost) AS gross_profit
FROM sal_delivery sal
JOIN sal_delivery_detail sald ON sald.order_id = sal.id
JOIN inv_stock inv ON inv.product_id = sald.product_id AND inv.warehouse_id = sal.warehouse_id
WHERE sal.bill_date BETWEEN '2026-06-01' AND '2026-06-30';
```

**注意**:`inv.avg_cost` 是出库时的快照成本,应从 `inv_ledger` 读(每笔出库已冻结成本)。

### Q4.4:信用额度如何控制?

**A**:在 `SalDeliveryService.createDelivery` 中:
```java
// 1. 累计客户应收
BigDecimal totalAR = finReceivableMapper.sumByCustomer(customerId);

// 2. 计算本次开单后应收
BigDecimal newAR = totalAR.add(dto.getTotalAmount());

// 3. 客户授信
BigDecimal creditLimit = customerMapper.getCreditLimit(customerId);

// 4. 校验
if (newAR.compareTo(creditLimit) > 0) {
    throw BizException.of("客户 [{}] 信用额度 {} 不足,当前应收 {},本次 {}",
        customerName, creditLimit, totalAR, dto.getTotalAmount());
}
```

### Q4.5:生产领料后,库存如何变化?

**A**:
1. 领料单审核 → `StockService.outStock(billType="PRD_PICK")`(扣减原料)
2. 成品入库 → `StockService.inStock(billType="PRD_FINISH")`(增加成品)
3. 成本归集:原料成本 + 人工 + 制造费用 = 成品入库成本

### Q4.6:多单位如何换算?

**A**:`base_product_unit` 表存换算关系,基本单位始终是 1。
```sql
-- 1 卷 = 100 米 = 50 公斤
INSERT INTO base_product_unit (product_id, unit_name, conversion_rate, is_base) VALUES
(1, '卷',  1,    1),
(1, '米',  100,  0),
(1, '公斤',50,   0);
```

下单时:
- 用户选「米」,数量 200
- 系统换算:`qty_in_base = 200 / 100 = 2 卷` → 入库 2 卷
- 显示给用户:200 米 = 2 卷

### Q4.7:对账怎么做?

**A**:应收对账:
```sql
-- 1. 客户所有发货
SELECT bill_no, total_amount, bill_date FROM sal_delivery
WHERE customer_id = ? AND bill_date BETWEEN ? AND ?;

-- 2. 客户所有收款
SELECT bill_no, pay_amount, pay_date FROM fin_receipt
WHERE customer_id = ? AND pay_date BETWEEN ? AND ?;

-- 3. 期初 + 累加 - 收款 = 期末
```

生成对账单 PDF / Excel,客户签字盖章回传。

### Q4.8:如何锁定某张单据不被并发修改?

**A**:**悲观锁**:
```java
SalOrder order = orderMapper.selectForUpdate(id);
// 业务逻辑
order.setStatus(2);
orderMapper.updateById(order);
```

MyBatis Plus `selectForUpdate`:
```java
@Select("SELECT * FROM sal_order WHERE id = #{id} FOR UPDATE")
SalOrder selectForUpdate(@Param("id") Long id);
```

**乐观锁**:
```java
@Version
private Integer version;

// 实体类加 @Version 字段,MP 自动 CAS
```

### Q4.9:打印如何对接针式打印机?

**A**:
1. 准备 `.ftl` 模板(见 `backend/src/main/resources/templates/print/`)
2. 后端 `PrintController` 输出 HTML
3. 浏览器 `window.print()` 或 Electron `webContents.print()`
4. 纸张设:宽度 21cm(80 列) / 24cm(132 列) / A4
5. CSS `@page { size: 21cm 9.7cm; margin: 0.5cm; }`

### Q4.10:数据如何备份?

**A**:
```bash
# 每日 02:00 全量备份
0 2 * * * /usr/local/bin/mysqldump -uroot -p'pass' erp | gzip > /backup/erp-$(date +\%Y\%m\%d).sql.gz

# 保留 30 天
find /backup -name "erp-*.sql.gz" -mtime +30 -delete
```

**恢复**:
```bash
gunzip < erp-20260616.sql.gz | mysql -uroot -p'pass' erp
```

## 五、异常类

### Q5.1:接口返回 `500 系统异常`,无具体信息

**A**:
- 检查 `GlobalExceptionHandler` 是否捕获
- 看后端日志:ERROR 级别
- 前端 Network → Response,后端 `msg` 应带错误描述

**全局异常返回示例**:
```json
{
  "code": 500,
  "msg": "客户 [恒力] 信用额度 50000 不足,当前应收 45000,本次 12000",
  "data": null,
  "ts": 1737014400000
}
```

### Q5.2:`403 Forbidden`

**A**:
- Token 无权限 → 检查 `@SaCheckPermission` 与 `sys_role_menu`
- 跨域 → 后端 `SaTokenConfig` CORS 配置
- 数据权限 → 用户 `dept_id` 与数据 `dept_id` 不一致

### Q5.3:`401 Unauthorized`

**A**:
- Token 过期 → 重新登录
- Header 缺失:`Authorization: Bearer xxx`
- 多端登录互踢:Sa-Token 配 `is-concurrent: false`

### Q5.4:`404 Not Found`

**A**:
- 接口路径错:看 `@RequestMapping`
- 前端代理配置:Nginx / Vite
- 后端 Controller 未加 `@RestController` / `@Controller`

### Q5.5:数据库死锁

**A**:
```sql
SHOW ENGINE INNODB STATUS\G
-- 看 LATEST DETECTED DEADLOCK
```

**预防**:
- 所有事务按**同一顺序**加锁(主表→从表)
- 索引覆盖:`WHERE` 条件必须走索引,否则行锁升级为表锁
- 短事务:避免在事务内调用 HTTP / RPC

### Q5.6:文件上传 500

**A**:
- 路径不存在:`uploadPath: /var/data/industrial-erp/upload/`,需 `mkdir -p`
- 磁盘满:`df -h`
- 权限:`chown -R app:app /var/data/industrial-erp/`

### Q5.7:启动后 CPU 立即 100%

**A**:
- 死循环:`jstack <pid> | grep -A 20 "RUNNABLE"`
- 频繁 GC:堆太小或内存泄漏
- 死锁:`jstack` 看 "Found one Java-level deadlock"

### Q5.8:应用跑一天后越来越慢

**A**:
- **内存泄漏**:用 `jmap -histo:live <pid>` 查大对象
- **连接池耗尽**:HikariCP 监控,看 `HikariPool-1 - Connection is not available`
- **ThreadLocal 未清理**:加 `try { ... } finally { ThreadLocal.remove(); }`
- **缓存未淘汰**:Redis 配 `maxmemory-policy: allkeys-lru`

### Q5.9:某条 SQL 突然变慢

**A**:
- 统计信息过期:`ANALYZE TABLE table_name;`
- 索引失效:加索引 / 改写 SQL
- 锁等待:`SHOW PROCESSLIST` 查长事务
- 数据膨胀:历史数据归档 / 分区表

### Q5.10:界面点击按钮无反应

**A**:
- F12 → Console 看 JS 报错
- Network → 接口是否发出
- 权限未配:v-permission 不显示按钮
- Loading 状态卡死:检查 axios 拦截器

## 六、升级与维护

### Q6.1:如何升级到新版本?

```bash
# 1. 备份
mysqldump -uroot -p erp > /backup/erp-$(date +%Y%m%d).sql

# 2. 拉代码
git pull origin main

# 3. 编译
mvn clean package -DskipTests

# 4. 跑迁移 SQL
mysql -uroot -p erp < sql/diff/2026XX_xxx.sql

# 5. 重启(零停机)
./scripts/start.sh --blue-green
```

### Q6.2:如何添加新客户?

1. 「系统管理 → 租户管理」(如有)→ 新建租户
2. 「系统管理 → 用户管理」→ 新建管理员,分配角色
3. 「基础资料 → 客户管理」→ 新建客户档案
4. 「基础资料 → 商品管理」→ 初始化商品
5. 「基础资料 → 仓库管理」→ 建仓库

### Q6.3:数据字典哪里维护?

`sys_dict` 表 + 「系统管理 → 数据字典」页面。前端通过 `dictService.getByType('xxx')` 拉取缓存。

### Q6.4:如何重置某用户密码?

```sql
-- 密码 admin123 (BCrypt 加密)
UPDATE sys_user SET password = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy' WHERE username = 'admin';
```

或「用户管理 → 重置密码」按钮。

## 七、效率工具

### Q7.1:接口调试工具
- **Knife4j**: `http://localhost:8080/doc.html`(推荐)
- **Apifox**: 团队协作
- **Postman**: 个人调试

### Q7.2:SQL 工具
- **Navicat**: 图形化
- **DBeaver**: 免费开源
- **MySQL Workbench**: 官方
- **DataGrip**: JetBrains(收费,功能强)

### Q7.3:Redis 工具
- **RedisInsight**: 官方 GUI
- **Another Redis Desktop Manager**: 免费

### Q7.4:日志查看
```bash
# 远程
ssh user@server "tail -f /var/log/industrial-erp/app.log"

# 实时关键字高亮
tail -f app.log | grep --color=auto -E "ERROR|WARN"

# 按时间
awk '/2026-06-16 14:00/,/2026-06-16 15:00/' app.log
```

### Q7.5:进程监控
```bash
# CPU / 内存 / 线程
top -Hp <pid>
jstack <pid> | less

# 内存
jmap -heap <pid>
jmap -histo:live <pid> | head -30
```

## 八、应急响应 Runbook

| 现象 | 第一时间动作 | 后续 |
|---|---|---|
| 服务完全挂 | 看进程在不在,直接重启;查 OOM 日志 | 加监控告警 |
| 接口全 500 | 看 DB / Redis 状态;查最近上线 | 回滚版本 |
| 数据错乱 | **立即停止写操作**;导出当前数据 | 排查 + 修复 + 追责 |
| 客户投诉 | 记录现象 + 时间 + 截图;查日志 | 给客户回复 + 复盘 |
| 安全事件 | 断网隔离;保留日志证据;改密码 | 复盘 + 加固 |

> **未在本 FAQ 列出的问题,提交 GitHub Issue,标签 `question`,24h 内回复。**
