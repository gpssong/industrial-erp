<template>
  <view class="container">
    <view class="card">
      <text class="title">📋 外勤盘点</text>
      <text class="muted">扫码或手动输入编码, 输入实盘数量, 提交后自动创建盘点单</text>
    </view>
    <view class="card">
      <view class="form-item">
        <text class="label">商品编码/条码</text>
        <view class="row" style="gap:6px">
          <input class="input" v-model="manualCode" placeholder="输入商品编码或条码" @confirm="onManualAdd" style="flex:1" />
          <button class="btn-sm" @click="onManualAdd" style="width:auto;padding:8px 16px">添加</button>
        </view>
      </view>
      <view class="row" style="gap:6px;margin-top:8px">
        <button class="btn" @click="onScan" style="flex:1">📷 扫码添加</button>
      </view>
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
      <view class="row" style="margin-top:6px">
        <text class="muted" @click="list.splice(i, 1)" style="cursor:pointer">🗑 删除</text>
      </view>
    </view>
    <view v-if="!list.length" class="empty">扫码或手动输入编码开始盘点</view>
    <view class="btn btn-block" @click="onClear" v-if="list.length" style="margin-top:10px;background:#95a5a6">清空列表</view>
    <view class="btn btn-block" @click="onSubmit" v-if="list.length" style="margin-top:10px;background:var(--success)">提交盘点 ({{ list.length }})</view>
  </view>
</template>
<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import api from '../../api/index.js'
import { doScan, stopScan } from '../../utils/scan.js'
import { checkPagePermission } from '../../utils/permission.js'
const list = ref([])
const manualCode = ref('')

function toast(msg) {
  if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: msg, icon: 'none' })
  else alert(msg)
}

async function searchAndAdd(kw) {
  if (!kw || !kw.trim()) {
    toast('请输入商品编码')
    return
  }
  kw = kw.trim()
  try {
    // App 端专用接口，不检查 base:product:list 权限
const r = await api.productAppSearch(kw)
    if (r && r.records && r.records.length > 0) {
      // 精确匹配: 优先 productCode 或 barcode
      const exact = r.records.find(p => p.productCode === kw || p.barcode === kw)
      const p = exact || r.records[0]
      // 防重复: 已存在则跳过
      if (list.value.find(x => x.productId === p.productId)) {
        toast('已在列表中: ' + p.productName)
        return
      }
      list.value.push({ ...p, bookQty: p.qty || 0, actualQty: p.qty || 0, remark: '' })
      if (!exact && r.records.length > 1) {
        toast('找到 ' + r.records.length + ' 个商品, 添加第一个: ' + p.productName)
      }
    } else {
      toast('商品未找到: ' + kw)
    }
  } catch (e) {
    toast('查询失败: ' + (e.msg || e.message || '网络错误'))
  }
}

function onScan() {
  doScan({
    onResult: (text) => searchAndAdd(text),
    onCancel: () => toast('已取消扫码'),
    onError: (err) => toast('扫码失败: ' + (err.message || err))
  })
}

function onManualAdd() {
  searchAndAdd(manualCode.value)
  manualCode.value = ''
}

function onClear() {
  if (confirm('确定清空列表?')) list.value = []
}

function onSubmit() {
  // P0-5: 之前直接 toast('已提交') + 清空 list, 但**没有调任何 API**, 操作员以为盘点已入账, 数据被本地丢弃.
  // 现说明此功能未接入后端, 防止误以为已完成.
  const tip = typeof uni !== 'undefined' && uni.showModal
    ? new Promise(resolve => uni.showModal({
        title: '盘点功能暂未上线',
        content: '当前版本盘点未对接后端 API, 点击确认仅清空本地列表, 数据不会保存到服务器.\n如需使用盘点功能, 请联系开发对接 /api/inventory/check 接口.',
        showCancel: false,
        success: r => resolve(r.confirm)
      }))
    : Promise.resolve(window.confirm('盘点功能暂未上线, 当前仅清空本地列表, 数据不会保存. 是否继续?'))
  tip.then(ok => {
    if (ok) {
      toast('已清空本地盘点列表 (未上传服务器)')
      list.value = []
    }
  })
}

// 退出页面时关闭摄像头
onUnmounted(() => { stopScan() })

onMounted(() => {
  // 权限校验
  if (!checkPagePermission('/pages/count/index')) {
    toast('无访问权限')
    setTimeout(() => window.history.back(), 500)
  }
})
</script>
<style scoped>
.form-item { margin: 4px 0; }
.label { display: block; font-size: 11px; color: #999; margin-bottom: 2px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #999; font-size: 12px; }
.empty { text-align: center; color: #999; padding: 40px; }
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.input:focus { border-color: #1e6091; outline: none; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; width: 100%; }
.btn-sm { background: #1e6091; color: #fff; padding: 6px 12px; border-radius: 4px; border: none; cursor: pointer; font-size: 13px; }
</style>