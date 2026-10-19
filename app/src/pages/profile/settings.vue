<template>
  <view class="container">
    <view class="card">
      <text class="title">⚙️ 服务器设置</text>
      <text class="muted">设置后需重新登录生效</text>
    </view>
    <view class="card">
      <view class="form-item">
        <text class="label">API 服务器地址</text>
        <input class="input" v-model="apiBase" placeholder="如 http://192.168.1.100:8080/api" />
      </view>
      <view class="btn btn-block" @click="onSave" style="margin-top:12px">保存并重启</view>
    </view>
    <view class="card">
      <text class="muted" style="font-size:12px">当前地址：{{ currentBase }}</text>
    </view>
  </view>
</template>
<script setup>
import { ref, onMounted } from 'vue'
const apiBase = ref('')
const currentBase = ref('')

// H5 浏览器: 默认走「同源 /api」(由部署侧 nginx 反代到 backend), 填了绝对地址才覆盖.
// 原生/Capacitor: 默认写死 http://home.93gushi.com:8088/api.
function isH5Runtime() {
  return typeof window !== 'undefined' && typeof document !== 'undefined' && typeof plus === 'undefined'
}
const NATIVE_DEFAULT = 'http://home.93gushi.com:8088/api'

function effectiveBase() {
  const override = uni.getStorageSync('erp_api_base') || ''
  if (isH5Runtime()) {
    if (override && /^https?:\/\//.test(override)) return override.replace(/\/$/, '')
    return (typeof window !== 'undefined' ? window.location.origin : '') + '/api (同源)'
  }
  return (override && /^https?:\/\//.test(override) ? override.replace(/\/$/, '') : NATIVE_DEFAULT)
}

onMounted(() => {
  apiBase.value = uni.getStorageSync('erp_api_base') || ''
  currentBase.value = effectiveBase()
})
function onSave() {
  const val = apiBase.value.trim()
  if (val) {
    uni.setStorageSync('erp_api_base', val)
    uni.showToast({ title: '已保存，将重启' })
  } else {
    uni.removeStorageSync('erp_api_base')
    uni.showToast({ title: '已恢复默认' })
  }
  currentBase.value = effectiveBase()
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
