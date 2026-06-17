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
const { autoUpdater } = require('electron-updater')
const log = require('electron-log')
const Store = require('electron-store')

const store = new Store({
  defaults: {
    apiBase: 'http://localhost:8080/api',
    webBase: 'http://localhost:5173',
    printName: '',
    autoStart: false,
    windowSize: { width: 1366, height: 800 }
  }
})

let mainWindow = null

function createWindow() {
  const { width, height } = store.get('windowSize')
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

  // 加载页面
  const devUrl = process.env.VITE_DEV_SERVER_URL
  if (devUrl) {
    mainWindow.loadURL(devUrl)
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  } else {
    // 加载打包后的 dist/index.html
    const indexPath = path.join(__dirname, '../../pc-web/dist/index.html')
    if (fs.existsSync(indexPath)) {
      mainWindow.loadFile(indexPath, { hash: '/dashboard' })
    } else {
      // 兜底: 加载远端
      mainWindow.loadURL(store.get('webBase'))
    }
  }

  mainWindow.once('ready-to-show', () => mainWindow.show())

  mainWindow.on('close', () => {
    const b = mainWindow.getBounds()
    store.set('windowSize', { width: b.width, height: b.height })
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
    message: '当前API地址: ' + store.get('apiBase'),
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
  const url = store.get('apiBase').replace('/api', '') + '/print/sales-delivery/1.html'
  win.loadURL(url)
  win.webContents.on('did-finish-load', () => {
    win.webContents.print({
      silent: false,
      printBackground: true,
      deviceName: store.get('printName') || ''
    }, (success, reason) => {
      if (!success) dialog.showErrorBox('打印失败', reason)
    })
  })
}

app.whenReady().then(() => {
  createWindow()
  buildMenu()
  // 开机自启
  if (store.get('autoStart')) app.setLoginItemSettings({ openAtLogin: true })

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('will-quit', () => globalShortcut.unregisterAll())
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })

// ============== IPC 桥接 ==============
ipcMain.handle('print:salesDelivery', async (e, id) => {
  const win = new BrowserWindow({ show: false })
  const url = store.get('apiBase').replace('/api', '') + '/print/sales-delivery/' + id + '.html'
  win.loadURL(url)
  return new Promise((resolve) => {
    win.webContents.on('did-finish-load', () => {
      win.webContents.print({
        silent: false, printBackground: true,
        deviceName: store.get('printName') || ''
      }, (success, reason) => {
        if (!success) log.error('打印失败:', reason)
        resolve({ success, reason })
        win.close()
      })
    })
  })
})

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

ipcMain.handle('settings:get', () => store.store)
ipcMain.handle('settings:set', (e, kv) => { store.set(kv); return true })
ipcMain.handle('app:version', () => app.getVersion())
