# Electron 桌面端 (Windows / macOS / Linux)

## 设计要点
- **代码复用**: 直接加载 Vue3 打包后的 dist, 复用 PC 后台 100% 代码
- **本地打印**: 通过 Electron `webContents.print()` 调用系统针式/激光打印机
- **离线开单**: 网络异常时本地缓存单据草稿, 联网后自动同步
- **开机自启**: `app.setLoginItemSettings({openAtLogin: true})`
- **快捷键**: F8 销售出库, F9 库存查询, F10 采购入库

## 构建

```bash
# 1. 先构建 PC 后台
cd ../pc-web && npm run build

# 2. 安装 Electron 依赖
cd ../electron && npm install

# 3. 开发模式 (需 Vite dev server 运行在 5173)
npm run dev

# 4. 打包 Windows 安装包
npm run build:win

# 5. 打包 macOS DMG
npm run build:mac
```

## 关键文件
- `src/main.js`: 主进程 (窗口/菜单/快捷键/打印)
- `src/preload.js`: 安全的 IPC 桥接
- `package.json`: 包含 electron-builder 配置 (NSIS / DMG)

## 打印机集成
- 针式打印机: 选择 `deviceName: 'EPSON LQ-630K'` 之类
- 小票打印机: 80mm / 58mm, 后端 `print` 模板已适配
- 模板: 后端 `/api/print/sales-delivery/{id}.html`

## 离线模式 (可选扩展)
- 使用 IndexedDB 缓存单据草稿
- 检测 navigator.onLine, 联网后批量提交
