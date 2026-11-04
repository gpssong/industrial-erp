<template>
  <view class="container">
    <view class="card">
      <text class="title">⚙️ 服务器设置</text>
      <text class="muted">设置后需重新登录生效</text>
    </view>
    <view class="card">
      <view class="form-item">
        <text class="label">API 服务器地址</text>
        <input class="input" v-model="apiBase" placeholder="如 http://47.96.188.129 或 http://192.168.1.100:8080/api" />
      </view>
      <view class="btn btn-block" @click="onSave" style="margin-top:12px">保存并重启</view>
    </view>
    <view class="card">
      <text class="muted" style="font-size:12px">当前地址：{{ currentBase }}</text>
    </view>

    <!-- v1.1.167 标签层 §9 取证入口 (刻意放这里而不是工作台, 别在生产界面露出)。
         取证做完 → docs/34 落定后由 Task B6 连同 pages/base/label-ble-debug 一起删除。 -->
    <view class="card">
      <text class="title">🧪 取证专用</text>
      <text class="muted">标签打印机蓝牙直连调试页 — 仅用于 docs/32 §9 的 IP-803 真机取证，
        不属于业务功能，上线前必须移除本页入口。</text>
      <view class="btn btn-block" @click="onOpenLabelBleDebug" style="margin-top:12px">打开标签蓝牙取证页</view>
    </view>
  </view>
</template>
<script setup>
import { ref, onMounted } from 'vue'
const apiBase = ref('')
const currentBase = ref('')

function onOpenLabelBleDebug() {
  uni.navigateTo({ url: '/pages/base/label-ble-debug' })
}

// H5 浏览器: 默认走「同源 /api」(由部署侧 nginx 反代到 backend), 填了绝对地址才覆盖.
// 原生/Capacitor: 默认写死 http://home.93gushi.com:8088/api.
// v1.1.105: 原判断只认 `plus`, 会把 Capacitor APK 误判成 H5, 页面显示「同源 /api」误导用户。
import { isH5Runtime } from '../../utils/runtime.js'
import { getApiBaseOverride, setApiBaseOverride } from '../../utils/apiBase.js'
const NATIVE_DEFAULT = 'http://home.93gushi.com:8088/api'

function effectiveBase() {
  const override = getApiBaseOverride()
  if (isH5Runtime()) {
    return override || ((typeof window !== 'undefined' ? window.location.origin : '') + '/api (同源)')
  }
  return override || NATIVE_DEFAULT
}

onMounted(() => {
  apiBase.value = getApiBaseOverride()
  currentBase.value = effectiveBase()
})
function onSave() {
  const saved = setApiBaseOverride(apiBase.value.trim())
  apiBase.value = saved
  currentBase.value = effectiveBase()
  uni.showToast({ title: saved ? '已保存，将重启' : '已恢复默认' })
  setTimeout(() => {
    uni.removeStorageSync('erp_user')
    uni.reLaunch({ url: '/pages/login/index' })
  }, 800)
}
</script>
<style scoped>
.form-item { margin: 8px 0; }
.label { display: block; font-size: 13px; color: #666; margin-bottom: 6px; }
</style>
