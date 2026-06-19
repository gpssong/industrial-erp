const { app, BrowserWindow, Menu } = require('electron')
const path = require('path')
const isDev = process.env.NODE_ENV === 'development'

function createWindow() {
  const win = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1100,
    minHeight: 700,
    title: '工业ERP',
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.cjs')
    }
  })

  // 菜单
  const menu = Menu.buildFromTemplate([
    { label: '工业ERP', submenu: [
      { label: '关于', click: () => {
        const { dialog } = require('electron')
        dialog.showMessageBox({ title: '关于', message: '工业ERP v1.0', detail: '薄膜/塑料/五金/加工行业ERP系统' })
      }},
      { type: 'separator' },
      { role: 'quit' }
    ]},
    { label: '编辑', submenu: [
      { role: 'undo' }, { role: 'redo' }, { type: 'separator' },
      { role: 'cut' }, { role: 'copy' }, { role: 'paste' }
    ]},
    { label: '视图', submenu: [
      { role: 'reload' }, { role: 'toggleDevTools' }, { type: 'separator' },
      { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { type: 'separator' },
      { role: 'togglefullscreen' }
    ]},
    { label: '窗口', submenu: [
      { role: 'minimize' }, { role: 'zoom' }, { role: 'close' }
    ]}
  ])
  Menu.setApplicationMenu(menu)

  if (isDev) {
    win.loadURL('http://localhost:5173')
  } else {
    win.loadFile(path.join(__dirname, '../dist/index.html'))
  }
}

app.whenReady().then(createWindow)
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
