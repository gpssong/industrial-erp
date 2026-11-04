<template>
  <view class="page">
    <view class="hd">标签蓝牙取证 (v1.1.167 · 闸前骨架)</view>
    <view class="warn">
      ⚠️ 取证专用页, 不属于业务功能。docs/33 Task B6 上线前必须删除本页 +
      settings.vue 里的入口卡 + LabelBlePlugin 注册。
    </view>

    <view class="row">
      <button class="btn" @click="doEnsurePerm">申请蓝牙权限</button>
      <button class="btn" @click="doScan">扫描</button>
      <button class="btn" @click="doStatus">查状态</button>
      <button class="btn" @click="doDisconnect">断开</button>
    </view>

    <view class="sec">设备 ({{ devices.length }})</view>
    <view v-if="!devices.length" class="empty">尚未扫描到设备 (只显示有名字的设备)</view>
    <view v-for="d in devices" :key="d.mac" class="dev" @click="pick(d)">
      {{ d.name || '(无名)' }} · {{ d.mac }} · {{ d.rssi }}dBm
    </view>

    <view class="sec">当前: {{ picked.mac || '未选择' }}</view>
    <button class="btn" @click="doConnect">连接</button>

    <view class="sec">原始字节 (base64 由这段文本逐字节 latin1 后转 base64)</view>
    <textarea v-model="raw" class="ta" placeholder="直接粘贴要发给打印机的指令文本" />
    <view class="row">
      <view class="field">
        <text class="lab">单片 MTU (留空=用协商值)</text>
        <input v-model="mtuInput" class="inp" type="number" placeholder="如 247 / 20" />
      </view>
      <view class="field">
        <text class="lab">片间隔 ms</text>
        <input v-model="delayInput" class="inp" type="number" placeholder="30" />
      </view>
    </view>
    <button class="btn" @click="doPrint">分片发送</button>

    <view class="sec">日志</view>
    <view v-for="(l, i) in logs" :key="i" class="log">{{ l }}</view>
  </view>
</template>

<script>
// 取证页: 不承担业务, 唯一作用是把「服务端渲染器产出的字节」原样送到打印机,
// 并把异常与分片数报出来 → 答案写进 docs/34_印麦IP803指令集实测.md.
//
// 与 docs/33 计划骨架的差别 (三处, 都是为了让取证跑得起来 / 读得出结果):
//   1) 多一颗「申请蓝牙权限」按钮 + LabelBle.ensurePermissions()。计划里 AndroidManifest
//      只做了声明, 而全仓此前没有任何运行时授权流程 → Android 12+ 上扫描/连接必抛
//      SecurityException, 现场没有任何授权入口, 取证 APK 直接是死的。
//   2) 把 print 的 mtu / chunkDelayMs 开放成输入框。docs/34 要记录的正是「这台机器
//      能吃多大的片」, 写死 30ms 间隔、拿不到 MTU 就答不了这个问题。
//   3) 日志里带上 connect / print 的 elapsedMs (A15 返修 M1)。docs/32 §9 除了「吃不吃这套
//      指令」还要看吞吐, 只靠日志的秒级时间戳量不出毫秒级的片速率。
import { registerPlugin } from '@capacitor/core'
import { isNativeApp } from '@/utils/runtime'

const LabelBle = registerPlugin('LabelBle')

function toBase64(str) {
  // 逐字节 latin1 → base64; 取证时编码问题 (GBK/UTF-8) 由服务端决定, 本页不猜
  let bin = ''
  for (let i = 0; i < str.length; i++) bin += String.fromCharCode(str.charCodeAt(i) & 0xff)
  return btoa(bin)
}

function toInt(v) {
  const n = parseInt(v, 10)
  return Number.isFinite(n) && n > 0 ? n : undefined
}

export default {
  data() {
    return { devices: [], picked: {}, raw: '', mtuInput: '', delayInput: '30', logs: [] }
  },
  methods: {
    say(t) {
      this.logs.push(`${new Date().toLocaleTimeString()} ${t}`)
    },
    guard() {
      if (!isNativeApp()) {
        this.say('本页只在 APK 里可用 (H5/浏览器没有原生蓝牙插件)')
        return false
      }
      return true
    },
    async doEnsurePerm() {
      if (!this.guard()) return
      try {
        const r = await LabelBle.ensurePermissions()
        this.say(`蓝牙权限 granted=${r.granted} (API ${r.apiLevel}, alias=${r.alias})`)
      } catch (e) {
        this.say('授权被拒: ' + ((e && (e.message || e.errMsg)) || e))
      }
    },
    async doScan() {
      if (!this.guard()) return
      this.say('扫描中…')
      try {
        const r = await LabelBle.scan({ timeoutMs: 6000 })
        this.devices = (r.devices || []).filter(d => d.name)
        this.say(`扫描完成, 有名设备 ${this.devices.length} 台`)
      } catch (e) {
        this.say('扫描失败: ' + ((e && (e.message || e.errMsg)) || e))
      }
    },
    pick(d) { this.picked = d; this.say(`选择 ${d.name} ${d.mac}`) },
    async doConnect() {
      if (!this.guard()) return
      if (!this.picked.mac) { this.say('先选一台设备'); return }
      try {
        const r = await LabelBle.connect({ mac: this.picked.mac })
        this.say(`已连接, mtu=${r.mtu} 单片=${r.chunkSize} 服务数=${r.serviceCount} 耗时=${r.elapsedMs}ms`)
      } catch (e) {
        this.say('连接失败: ' + ((e && (e.message || e.errMsg)) || e))
      }
    },
    async doStatus() {
      if (!this.guard()) return
      try {
        const s = await LabelBle.status()
        this.say(`status connected=${s.connected} mtu=${s.mtu} mac=${s.mac} name=${s.name}`)
      } catch (e) { this.say('状态查询失败: ' + e) }
    },
    async doDisconnect() {
      if (!this.guard()) return
      try { await LabelBle.disconnect(); this.say('已断开') }
      catch (e) { this.say('断开失败: ' + e) }
    },
    async doPrint() {
      if (!this.guard()) return
      if (!this.raw) { this.say('文本为空'); return }
      const payload = { contentBase64: toBase64(this.raw) }
      const mtu = toInt(this.mtuInput)
      const delay = parseInt(this.delayInput, 10)
      if (mtu) payload.mtu = mtu
      if (Number.isFinite(delay) && delay >= 0) payload.chunkDelayMs = Math.min(delay, 200)
      this.say(`准备发送 ${this.raw.length} 字符 → base64 ${payload.contentBase64.length} 字符`)
      try {
        const r = await LabelBle.print(payload)
        this.say(`已发 ${r.sentBytes} 字节 / ${r.chunks} 片 (片大小 ${r.chunkSize}, mtu ${r.mtu}, 无回调 ${r.missingAcks} 片, 耗时 ${r.elapsedMs}ms)`)
      } catch (e) {
        this.say('发送失败: ' + ((e && (e.message || e.errMsg)) || e))
      }
    }
  }
}
</script>

<style>
.page { padding: 24rpx; }
.hd { font-size: 32rpx; font-weight: 600; margin-bottom: 12rpx; }
.warn { font-size: 22rpx; color: #b45309; background: #fef3c7; padding: 14rpx; border-radius: 8rpx; }
.row { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 16rpx; }
.btn { font-size: 24rpx; }
.sec { margin: 24rpx 0 8rpx; font-size: 26rpx; color: #666; }
.empty { font-size: 24rpx; color: #999; padding: 12rpx 0; }
.dev { padding: 16rpx; border-bottom: 1px solid #eee; font-size: 26rpx; }
.ta { width: 100%; height: 240rpx; border: 1px solid #ddd; padding: 12rpx; font-size: 24rpx; }
.field { flex: 1; min-width: 260rpx; }
.lab { display: block; font-size: 22rpx; color: #666; margin-bottom: 6rpx; }
.inp { border: 1px solid #ddd; padding: 10rpx; font-size: 24rpx; }
.log { font-size: 22rpx; color: #444; padding: 4rpx 0; word-break: break-all; }
</style>
