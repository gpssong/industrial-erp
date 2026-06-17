/**
 * 预加载脚本 - 暴露安全的 API 给渲染进程
 */
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('erpDesktop', {
  print: {
    salesDelivery: (id) => ipcRenderer.invoke('print:salesDelivery', id),
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
