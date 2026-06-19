// preload script - 安全桥接 electron 和渲染进程
const { contextBridge, ipcRenderer } = require('electron')
contextBridge({
  electron: { platform: process.platform }
})
