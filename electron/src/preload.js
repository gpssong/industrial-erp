/**
 * 预加载脚本 - 暴露安全的 API 给渲染进程
 */
const { contextBridge, ipcRenderer } = require('electron')

// 注入环境变量: 告诉前端 axios 使用完整 API URL (解决 file:// 协议下 /api 路径解析问题)
const { app } = require('electron')
const path = require('path')
const fs = require('fs')
try {
  const configPath = path.join(app.getPath('userData'), 'config.json')
  const cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8'))
  // 将 apiBase 注入到 window.__ERP_API_BASE__，前端会读取它
  contextBridge.exposeInMainWorld('__ERP_API_BASE__', cfg.apiBase || 'http://home.93gushi.com:8088/api')
} catch (e) {
  contextBridge.exposeInMainWorld('__ERP_API_BASE__', 'http://home.93gushi.com:8088/api')
}

contextBridge.exposeInMainWorld('erpDesktop', {
  print: {
    // print:salesDelivery / print:prdOrder 已废弃 — 前端改用 myprint-design 客户端打印
    list: () => ipcRenderer.invoke('print:list')
  },
  settings: {
    get: () => ipcRenderer.invoke('settings:get'),
    set: (kv) => ipcRenderer.invoke('settings:set', kv)
  },
  app: {
    version: () => ipcRenderer.invoke('app:version')
  },
  onShortcut: (cb) => ipcRenderer.on('shortcut', (_, data) => cb(data))
})
