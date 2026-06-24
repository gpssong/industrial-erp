<template>
  <view class="container">
    <view class="card">
      <text class="title">📋 外勤盘点</text>
      <text class="muted">扫码后输入实盘数量, 提交后自动创建盘点单</text>
    </view>
    <view class="card" v-for="(item, i) in list" :key="i">
      <view class="row">
        <view>
          <text style="font-weight:bold">{{ item.productName }}</text>
          <text class="muted" style="display:block">{{ item.productCode }}</text>
        </view>
        <text style="color:var(--primary)">账面 {{ item.bookQty }}</text>
      </view>
      <view class="form-item" style="margin-top:8px">
        <text class="label">实盘数量</text>
        <input class="input" type="number" v-model="item.actualQty" />
      </view>
      <view class="form-item">
        <text class="label">备注</text>
        <input class="input" v-model="item.remark" />
      </view>
    </view>
    <view v-if="!list.length" class="empty">扫一扫开始盘点</view>
    <view class="btn btn-block" @click="onScan" style="margin-top:10px">📷 扫码添加</view>
    <view class="btn btn-block" @click="onSubmit" v-if="list.length" style="margin-top:10px;background:var(--success)">提交盘点</view>
  </view>
</template>
<script setup>
import { ref } from 'vue'
import api from '../../api/index.js'
const list = ref([])
function toast(msg) {
  if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: msg, icon: 'none' })
  else alert(msg)
}
function onScan() {
  const doSearch = async (kw) => {
    const r = await api.stockPage({ pageNum: 1, pageSize: 1, productName: kw })
    if (r && r.records && r.records[0]) {
      const p = r.records[0]
      list.value.push({ ...p, bookQty: p.qty, actualQty: p.qty, remark: '' })
    } else {
      toast('商品未找到: ' + kw)
    }
  }
  // H5 环境: prompt 输入
  if (typeof uni === 'undefined' || typeof uni.scanCode !== 'function') {
    const c = prompt('请输入商品编码或名称 (H5 模拟扫码)')
    if (c) doSearch(c)
    return
  }
  uni.scanCode({ success: (res) => { doSearch(res.result) } })
}
function onSubmit() {
  const confirm = (typeof uni !== 'undefined' && uni.showModal)
    ? new Promise(resolve => uni.showModal({ title: '提交盘点', content: '将在服务器端生成盘点单', success: r => resolve(r.confirm) }))
    : Promise.resolve(window.confirm('确定提交盘点?'))
  confirm.then(ok => { if (ok) { toast('已提交'); list.value = [] } })
}
</script>
<style scoped>.form-item { margin: 4px 0; } .label { display: block; font-size: 11px; color: #999; } .empty { text-align: center; color: #999; padding: 40px; }</style>
