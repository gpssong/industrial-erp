<template>
  <view class="container">
    <view class="card">
      <text class="title">修改密码</text>
    </view>

    <view class="card">
      <view class="form-item">
        <text class="label">旧密码 *</text>
        <input class="input" v-model="form.oldPassword" type="safe-password" placeholder="请输入旧密码" />
      </view>
      <view class="form-item">
        <text class="label">新密码 *</text>
        <input class="input" v-model="form.newPassword" type="safe-password" placeholder="请输入新密码" />
      </view>
      <view class="form-item">
        <text class="label">确认新密码 *</text>
        <input class="input" v-model="form.confirmPassword" type="safe-password" placeholder="请再次输入新密码" />
      </view>
    </view>

    <view style="padding: 16px;">
      <button class="btn btn-block" @click="onSubmit" :loading="submitting">确定</button>
    </view>
  </view>
</template>

<script setup>
import { reactive, ref } from 'vue'
import api from '../../api/index.js'

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const submitting = ref(false)

function toast(msg) {
  uni.showToast({ title: msg, icon: msg.includes('成功') ? 'success' : 'none' })
}

async function onSubmit() {
  if (!form.oldPassword) { toast('请输入旧密码'); return }
  if (!form.newPassword) { toast('请输入新密码'); return }
  if (form.newPassword.length < 6) { toast('密码至少6位'); return }
  if (form.newPassword !== form.confirmPassword) { toast('两次密码不一致'); return }

  submitting.value = true
  try {
    // 走共享 api.changeMyPassword: H5 用 cookie 自动带, 原生 App 走共享 request() 兜底
    await api.changeMyPassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    toast('修改成功, 请重新登录')
    // 清除登录态 (token + userInfo), 后端会通过 Set-Cookie MaxAge=0 清 cookie
    try { uni.removeStorageSync('erp_token') } catch (e) {}
    try { localStorage.removeItem('erp_token') } catch (e) {}
    try { uni.removeStorageSync('erp_user') } catch (e) {}
    try { localStorage.removeItem('erp_user') } catch (e) {}
    setTimeout(() => { uni.reLaunch({ url: '/pages/login/index' }) }, 1500)
  } catch (e) {
    toast('修改失败: ' + (e.msg || e.message || '网络错误'))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.container { padding: 12px; background: #f5f5f5; min-height: 100vh; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.title { font-size: 15px; font-weight: bold; display: block; margin-bottom: 4px; }
.form-item { margin: 8px 0; }
.label { display: block; font-size: 12px; color: #999; margin-bottom: 4px; }
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; width: 100%; }
.btn-block { width: 100%; }
</style>