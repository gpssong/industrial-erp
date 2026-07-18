/**
 * Electron 主进程
 *  - 加载 Vue3 打包后的 dist (或开发时访问 Vite)
 *  - 启动本地服务 (反代接口)
 *  - 调用系统打印机 (本地高速打印)
 *  - 开机自启动 / 快捷键
 *  - 全局快捷键 F8 = 销售开单, F9 = 库存查询
 */
const { app, BrowserWindow, ipcMain, dialog, shell, globalShortcut, Menu } = require('electron')
const path = require('path')
const fs = require('fs')

// 简单的 JSON 配置文件存储 (替代 electron-store)
const CONFIG_FILE = path.join(app.getPath('userData'), 'config.json')
const DEFAULT_CONFIG = {
  apiBase: 'http://home.93gushi.com:8088/api',
  webBase: 'http://home.93gushi.com:8088',
  printName: '',
  autoStart: false,
  windowSize: { width: 1366, height: 800 }
}
function loadConfig() {
  try { return Object.assign({}, DEFAULT_CONFIG, JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf-8'))) }
  catch (e) { return { ...DEFAULT_CONFIG } }
}
function saveConfig(cfg) { try { fs.writeFileSync(CONFIG_FILE, JSON.stringify(cfg, null, 2)) } catch (e) {} }
let store = loadConfig()

let mainWindow = null

function createWindow() {
  const { width, height } = store.windowSize
  mainWindow = new BrowserWindow({
    width, height, minWidth: 1024, minHeight: 700,
    title: '工业ERP - 桌面客户端',
    icon: path.join(__dirname, '../build/icon.ico'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    },
    show: false,
    backgroundColor: '#1e6091'
  })

  // 优先从远端加载 (避免 file:// 协议下 Vite 哈希资源缓存问题)
  // 仅当网络不可用时才降级到本地 dist
  const resourcePath = process.resourcesPath
  const distExists = fs.existsSync(path.join(resourcePath, 'pc-web-dist', 'index.html'))
  const devUrl = process.env.VITE_DEV_SERVER_URL

  if (devUrl) {
    mainWindow.loadURL(devUrl)
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  } else {
    // 尝试加载远端 (推荐路径, 避免 file:// 缓存)
    const remoteUrl = store.webBase + '/#/login'
    mainWindow.loadURL(remoteUrl).catch(() => {
      // 网络不可用时降级到本地 dist
      if (distExists) {
        const distPath = path.join(resourcePath, 'pc-web-dist', 'index.html')
        mainWindow.loadFile(distPath)
      }
    })
  }

  // 监听加载失败, 降级到本地 dist
  let failedToLoadRemote = false
  mainWindow.webContents.on('did-fail-load', (e, code, desc) => {
    console.error('[main.js] did-fail-load:', code, desc)
    if (!failedToLoadRemote && distExists) {
      failedToLoadRemote = true
      const distPath = path.join(resourcePath, 'pc-web-dist', 'index.html')
      console.log('[main.js] Falling back to local dist:', distPath)
      mainWindow.loadFile(distPath)
    }
  })
  mainWindow.webContents.on('console-message', (e, level, message) => {
    console.log('[renderer]', message)
  })
  // 优先从远端加载 (避免 file:// 协议下 hash 路由的 base 路径问题)
  // 启动延迟 500ms 后再 load, 让 web-contents 完全就绪
  mainWindow.webContents.once('did-finish-load', () => {
    console.log('[main.js] page loaded:', mainWindow.webContents.getURL())
  })
  mainWindow.webContents.on('render-process-gone', (e, details) => {
    console.error('[main.js] render-process-gone:', details)
  })
  mainWindow.once('ready-to-show', () => mainWindow.show())

  mainWindow.on('close', () => {
    const b = mainWindow.getBounds()
    Object.assign(store, { windowSize: { width: b.width, height: b.height } })
    saveConfig(store)
  })

  mainWindow.on('closed', () => { mainWindow = null })

  // 注册全局快捷键
  globalShortcut.unregisterAll()
  globalShortcut.register('F8', () => {
    mainWindow?.webContents.send('shortcut', { type: 'OPEN_SALES' })
    mainWindow?.loadURL(mainWindow.webContents.getURL().split('#')[0] + '#/sales/delivery')
  })
  globalShortcut.register('F9', () => {
    mainWindow?.webContents.send('shortcut', { type: 'OPEN_STOCK' })
    mainWindow?.loadURL(mainWindow.webContents.getURL().split('#')[0] + '#/inventory/stock')
  })
  globalShortcut.register('F10', () => {
    mainWindow?.webContents.send('shortcut', { type: 'OPEN_PURCHASE' })
    mainWindow?.loadURL(mainWindow.webContents.getURL().split('#')[0] + '#/purchase/receipt')
  })
}

function buildMenu() {
  const template = [
    {
      label: '系统',
      submenu: [
        { label: '工作台', click: () => mainWindow.loadURL(mainWindow.webContents.getURL().split('#')[0] + '#/dashboard') },
        { type: 'separator' },
        { label: '设置 API', click: () => openSettings() },
        { type: 'separator' },
        { label: '退出', role: 'quit' }
      ]
    },
    {
      label: '业务',
      submenu: [
        { label: '销售出库 (F8)', accelerator: 'F8', click: () => mainWindow.loadURL(mainWindow.webContents.getURL().split('#')[0] + '#/sales/delivery') },
        { label: '采购入库 (F9)', accelerator: 'F9', click: () => mainWindow.loadURL(mainWindow.webContents.getURL().split('#')[0] + '#/purchase/receipt') },
        { label: '库存查询 (F10)', accelerator: 'F10', click: () => mainWindow.loadURL(mainWindow.webContents.getURL().split('#')[0] + '#/inventory/stock') }
      ]
    },
    {
      label: '工具',
      submenu: [
        { label: '本地打印测试', click: () => printLocal() },
        { label: '打开数据目录', click: () => shell.openPath(app.getPath('userData')) },
        { label: '开发者工具', accelerator: 'F12', click: () => mainWindow.webContents.openDevTools() }
      ]
    },
    { label: '帮助', submenu: [{ label: '关于', click: () => showAbout() }] }
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

async function openSettings() {
  const r = await dialog.showMessageBox(mainWindow, {
    type: 'info',
    title: '系统设置',
    message: '当前API地址: ' + store.apiBase,
    buttons: ['修改', '取消']
  })
  if (r.response === 0) {
    // 简化: 实际可弹窗
  }
}

function showAbout() {
  dialog.showMessageBox(mainWindow, {
    type: 'info',
    title: '关于',
    message: '工业ERP v1.0.0\n\n企业级工业进销存 ERP 系统\n薄膜/塑料/五金/加工/工贸一体'
  })
}

/**
 * 本地打印: 通过 webContents.print() 调用系统打印机
 * 优点: 支持任意针式/激光/小票打印机
 */
async function printLocal() {
  const win = new BrowserWindow({ show: false, webPreferences: { sandbox: true } })
  const url = store.apiBase.replace('/api', '') + '/print/sales-delivery/1.html'
  win.loadURL(url)
  win.webContents.on('did-finish-load', () => {
    win.webContents.print({
      silent: false,
      printBackground: true,
      deviceName: store.printName || ''
    }, (success, reason) => {
      if (!success) dialog.showErrorBox('打印失败', reason)
    })
  })
}

app.whenReady().then(() => {
  createWindow()
  buildMenu()
  // 开机自启
  if (store.autoStart) app.setLoginItemSettings({ openAtLogin: true })

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('will-quit', () => globalShortcut.unregisterAll())
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })

// ============== IPC 桥接 ==============
// print:salesDelivery / print:prdOrder 已废弃 — 前端改用 myprint-design 客户端打印,
// 不再依赖后端静态 HTML 页面. 保留 print:list (获取系统打印机列表) 和 settings/app.

ipcMain.handle('print:list', async () => {
  // Electron 没有直接 API 列出系统打印机, 借助 webContents.getPrintersAsync
  if (mainWindow) {
    try {
      const list = await mainWindow.webContents.getPrintersAsync()
      return list
    } catch (e) { return [] }
  }
  return []
})

ipcMain.handle('settings:get', () => store)
ipcMain.handle('settings:set', (e, kv) => { Object.assign(store, kv); saveConfig(store); return true })
ipcMain.handle('app:version', () => app.getVersion())
