<template>
  <view class="app">
    <!-- uni-app 页面由 pages.json 管理，无需 router-view -->
  </view>
</template>
<script setup>
import { onLaunch } from '@dcloudio/uni-app'
onLaunch(() => {
  console.log('App Launch')
})
</script>
<style>
@import './static/css/common.css';
</style>
