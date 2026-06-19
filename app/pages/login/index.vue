<template>
  <view class="login-page">
    <view class="header">
      <text class="logo">🏭</text>
      <text class="title">工业ERP</text>
      <text class="subtitle">薄膜/塑料/五金/加工</text>
    </view>
    <view class="form card">
      <view class="form-item">
        <text class="label">账号</text>
        <input class="input" v-model="form.username" placeholder="请输入账号" />
      </view>
      <view class="form-item">
        <text class="label">密码</text>
        <input class="input" password v-model="form.password" placeholder="请输入密码" />
      </view>
      <view class="btn btn-block" @click="onLogin">登 录</view>
    </view>
    <view class="muted" style="text-align:center;margin-top:20px">默认 admin / admin123</view>
  </view>
</template>
<script setup>
import { reactive } from 'vue'
import api from '../../api/index.js'
const form = reactive({ username: 'admin', password: 'admin123' })
async function onLogin() {
  try {
    const r = await api.login(form)
    uni.setStorageSync('erp_token', r.token)
    uni.setStorageSync('erp_user', JSON.stringify(r))
    uni.switchTab({ url: '/pages/dashboard/index' })
  } catch (e) {}
}
</script>
<style scoped>
.login-page { padding: 40px 20px; }
.header { text-align: center; margin-bottom: 30px; }
.logo { font-size: 60px; display: block; }
.title { display: block; font-size: 22px; font-weight: bold; color: var(--primary); margin-top: 8px; }
.subtitle { display: block; font-size: 12px; color: var(--muted); margin-top: 4px; }
.form-item { margin-bottom: 14px; }
.label { display: block; font-size: 13px; color: #666; margin-bottom: 4px; }
</style>
