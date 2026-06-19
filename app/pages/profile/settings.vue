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
onMounted(() => {
  currentBase.value = uni.getStorageSync('erp_api_base') || '未设置（使用默认）'
  apiBase.value = uni.getStorageSync('erp_api_base') || ''
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
  setTimeout(() => {
    uni.removeStorageSync('erp_token')
    uni.removeStorageSync('erp_user')
    uni.reLaunch({ url: '/pages/login/index' })
  }, 800)
}
</script>
<style scoped>
.form-item { margin: 8px 0; }
.label { display: block; font-size: 13px; color: #666; margin-bottom: 6px; }
</style>
