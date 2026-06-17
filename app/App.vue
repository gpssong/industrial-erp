<template>
  <view class="app">
    <router-view />
  </view>
</template>
<script setup>
import { onLaunch, onShow } from '@dcloudio/uni-app'
onLaunch(() => {
  console.log('App Launch')
  // 检查登录态
  const token = uni.getStorageSync('erp_token')
  if (!token) uni.reLaunch({ url: '/pages/login/index' })
})
</script>
<style>
@import './static/css/common.css';
</style>
