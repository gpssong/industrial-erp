# 工业 ERP 系统 (industrial-erp)

Spring Boot 3.2.5 + MyBatis Plus 3.5.9 + JDK 17 + Vue 3 + uni-app (Capacitor 6)

部署在 Synology DS918+ 容器内: 后端 8080 / PC Web 18080 / App H5 18090 / 统一反代 8088
外部域名: `home.93gushi.com`

完整部署文档见 `~/.claude/projects/-Users-tongban/memory/erp-nas-deployment-overview.md`

## 飞鹅云打印架构 (v1.0.4+)

### 两条独立打印路径

| 路径 | 入口 | 模板 | 渲染引擎 |
|---|---|---|---|
| **PC 浏览器打印** | 各单据"打印"按钮 → `usePrint.js: doPrint` | `sys_print_template.content` (myprint-design JSON) | myprint-design v6 + Chrome 打印对话框 |
| **飞鹅云打印** | 各单据"飞鹅打印"按钮 → `FeiePrintClient.printMsg` | `sys_feie_print_template.content` (用户自定义) 或 ftl 内置模板 | FreeMarker 渲染后发飞鹅云 |

### 飞鹅打印关键代码

- **客户端**: `backend/.../production/client/FeiePrintClient.java` — SHA1 签名调用 `https://api.feieyun.cn/Api/Open/`
- **服务**: `backend/.../production/service/FeiePrintService.java` — 加载模板、渲染、提交飞鹅云、写日志
- **加载器 (BillLoader)**: `backend/.../production/bill/` 每个 bizType 一个 loader
  - `PrdOrderBillLoader` (生产加工单)
  - `SalDeliveryBillLoader` (销售出库单)
  - `SalReturnBillLoader` (销售退货单)
  - `PurReceiptBillLoader` (采购入库单)
  - `PurReturnBillLoader` (采购退货单)
  - `InvCheckBillLoader` (盘点单)
- **FreeMarker 模板**: `backend/src/main/resources/print/*_feie.ftl`
- **模板字段说明**: `pc-web/src/views/system/FeiePrintTemplate.vue` 的 `FIELD_DOC`
  - 主表用 `${order.xxx}` (PRD_ORDER) / `${bill.xxx}` (其他)
  - 明细行用 `${d.xxx}` (`<#list bill.details as d>` 循环内)
- **飞鹅账号**: `gpssong@163.com` / UKEY `cY9qWSXLgDDYwQYP` / SN `916503246`
  - 联调时录入 `sys_feie_printer_config` 表

### 飞鹅签名算法
```
SHA1(user + ukey + stime).hexdigest()   // 小写 40 字符
stime = unix 秒数
```

## 模板字段注入 (colorNo 模型)

商品 (BaseProduct) 上有 `colorNo` (色号) 字段。打印时所有单据的明细/主表需要它:

| 路径 | 注入位置 |
|---|---|
| PC 浏览器打印 (PRD_ORDER) | `PrdOrderService.detail()` 注入主表 `order.colorNo` |
| PC 浏览器打印 (SAL_DELIVERY / PUR_RECEIPT) | `SalDeliveryService.detail()` / `PurReceiptService.detail()` 注入明细 `detail.colorNo` |
| 飞鹅打印 (PRD_ORDER) | `PrdOrderBillLoader.load()` 注入主表 `order.colorNo` |
| 飞鹅打印 (SAL_DELIVERY / PUR_RECEIPT) | `SalDeliveryBillLoader.load()` / `PurReceiptBillLoader.load()` 注入明细 `detail.colorNo` |

实现: transient 字段 `pColorNo` + getter `getColorNo()` 让 FreeMarker/myprint 都能访问。

生产单编辑页面 (`pc-web/src/views/production/Order.vue`) 加色号输入框，`onProductChange` 自动从商品库带出。

## 飞鹅云打印字段访问关键

FreeMarker 访问 order.colorNo 时:
- PrdOrder 实体定义了 `getColorNo()` 返回 `pColorNo`
- BillLoader.load() 必须先调 `order.setPColorNo(prod.getColorNo())`
- 否则渲染时空字符串

## 已修复的坑

1. **双重 UTF-8 编码**: 早期飞鹅打印机相关菜单写入 DB 时被双重 UTF-8 编码, 导致显示 mojibake. 通过 SQL UPDATE 直接修复 9 条菜单的 `menu_name` 字段 (id 952-960).
2. **Vite tree-shaking 删方法**: App 端 `api.changeMyPassword` 等方法被 tree-shake 删除, 修改密码页改用 `uni.request` 直接调用绕过.
3. **PKG FTL 双 FreeMarkerConfig**: 注入 `@Qualifier("feieFreemarkerConfig")` 解决 Mac.swp 报错.
4. **飞鹅 host**: 用 `api.feieyun.cn` 而非 `cloud.feieyun.cn`.

## 数据库

- MySQL 8.0 root 密码 `erp_root_pwd` (从 .env 读)
- 数据库 `industrial_erp` (utf8mb4)
- 容器 erp-mysql, 端口 3306
- 数据卷: `/volume3/docker/erp-system/mysql-data`

## 前端结构

- `pc-web/` — PC Web (Vue 3 + Element Plus + Vite)
- `app/` — uni-app 编译产物 (Vue 3 + Capacitor 6 打包 APK)
- `backend/` — Spring Boot 后端
- `sql/` — 数据库初始化脚本

## App 端 (uni-app) 关键点

- 构建: `npm run build:h5` → 输出 `dist/build/h5/`
- APK 打包:
  ```
  rm -rf dist/build/h5 && npm run build:h5
  rm -rf android/app/src/main/assets/public/* && cp -R dist/build/h5/* android/app/src/main/assets/public/
  cd android && ./gradlew clean assembleDebug
  cp app/build/outputs/apk/debug/app-debug.apk ~/Desktop/鹏程ERP-debug.apk
  ```
- 默认 API 地址: `http://home.93gushi.com:8088/api` (可被 localStorage `erp_api_base` 覆盖)
- 路由: `app/src/pages.json`