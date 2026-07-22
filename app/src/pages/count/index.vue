<template>
  <view class="container">
    <view class="card">
      <text class="title">📋 外勤盘点</text>
      <text class="muted">选仓库 + 扫码录入实盘, 提交后生成 DRAFT 盘点单, 待 PC 端审核调整库存</text>
    </view>

    <!-- 仓库选择 + 预填 -->
    <view class="card">
      <view class="form-item">
        <text class="label">仓库 *</text>
        <view class="row" style="gap:6px">
          <picker mode="selector" :range="warehouseOptions" range-key="label" :value="warehouseIdx" @change="onWarehouseChange">
            <view class="picker">
              {{ warehouseOptions[warehouseIdx]?.label || '点击选择仓库' }}
            </view>
          </picker>
        </view>
      </view>
      <view class="row" style="gap:6px;margin-top:8px">
        <button class="btn" @click="onLoadSnapshot" style="flex:1;background:#909399" :disabled="!warehouseId">📦 从账面预填</button>
        <button class="btn" @click="onScan" style="flex:1">📷 扫码添加</button>
      </view>
      <view class="form-item" style="margin-top:8px">
        <text class="label">备注</text>
        <input class="input" v-model="remark" placeholder="可选: 盘点说明" />
      </view>
    </view>

    <!-- 商品列表 (扫码或手动添加) -->
    <view class="card">
      <view class="form-item">
        <text class="label">扫码或手动添加</text>
        <view class="row" style="gap:6px">
          <input class="input" v-model="manualCode" placeholder="商品编码或条码" @confirm="onManualAdd" style="flex:1" />
          <button class="btn-sm" @click="onManualAdd" style="width:auto;padding:8px 16px">添加</button>
        </view>
      </view>
    </view>

    <view class="card" v-for="(item, i) in list" :key="item._key">
      <view class="row">
        <view style="flex:1">
          <text style="font-weight:bold">{{ item.productName }}</text>
          <text class="muted" style="display:block">{{ item.productCode }}</text>
        </view>
        <text class="muted">账面 {{ item.bookQty }}</text>
      </view>
      <view class="form-item" style="margin-top:8px">
        <text class="label">实盘数量 *</text>
        <input class="input" type="number" v-model="item.actualQty" placeholder="输入实盘数量" />
      </view>
      <view class="row" style="margin-top:4px;font-size:12px">
        <text :style="{ color: diff(item) > 0 ? '#67c23a' : diff(item) < 0 ? '#f56c6c' : '#999' }">
          差异 {{ diff(item) }}
        </text>
        <text class="muted" @click="list.splice(i, 1)" style="cursor:pointer">🗑 删除</text>
      </view>
    </view>

    <view v-if="!list.length" class="empty">扫码 / 手动添加 / 从账面预填开始盘点</view>

    <view v-if="list.length" class="summary">
      共 {{ list.length }} 项 | 盘盈 <text style="color:#67c23a">{{ summary.profit }}</text> |
      盘亏 <text style="color:#f56c6c">{{ summary.loss }}</text>
    </view>

    <view class="btn btn-block" @click="onClear" v-if="list.length" style="margin-top:10px;background:#95a5a6">清空列表</view>
    <view class="btn btn-block" @click="onSubmit" v-if="list.length" style="margin-top:10px;background:var(--success)" :disabled="submitting">
      {{ submitting ? '提交中...' : '提交盘点 (' + list.length + ')' }}
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import api from '../../api/index.js'
import { doScan, stopScan } from '../../utils/scan.js'
import { checkPagePermission } from '../../utils/permission.js'

const list = ref([])
const manualCode = ref('')
const remark = ref('')
const submitting = ref(false)
const warehouseId = ref(null)
const warehouseIdx = ref(0)
const warehouseOptions = ref([])

function toast(msg) {
  if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title: String(msg).substring(0, 30), icon: 'none', duration: 2000 })
  else alert(msg)
}

async function loadWarehouses() {
  try {
    const list = (await api.warehouseList()) || []
    warehouseOptions.value = list.map(w => ({ id: w.id, label: w.warehouseName }))
    if (warehouseOptions.value.length > 0 && !warehouseId.value) {
      warehouseId.value = warehouseOptions.value[0].id
      warehouseIdx.value = 0
    }
  } catch (e) {
    toast('加载仓库列表失败: ' + (e.msg || e.message || ''))
  }
}

function onWarehouseChange(e) {
  const idx = Number(e.detail.value)
  warehouseIdx.value = idx
  warehouseId.value = warehouseOptions.value[idx]?.id || null
  // 切换仓库清空列表
  list.value = []
}

function diff(item) {
  const a = Number(item.actualQty || 0)
  const b = Number(item.bookQty || 0)
  return (a - b).toFixed(4).replace(/\.?0+$/, '')
}

const summary = computed(() => {
  let profit = 0, loss = 0
  for (const it of list.value) {
    const d = Number(it.actualQty || 0) - Number(it.bookQty || 0)
    if (d > 0) profit += d
    else if (d < 0) loss += Math.abs(d)
  }
  return { profit: profit.toFixed(4).replace(/\.?0+$/, ''), loss: loss.toFixed(4).replace(/\.?0+$/, '') }
})

async function searchAndAdd(kw) {
  if (!kw || !kw.trim()) {
    toast('请输入商品编码')
    return
  }
  if (!warehouseId.value) {
    toast('请先选择仓库')
    return
  }
  kw = kw.trim()
  try {
    const r = await api.productAppSearch(kw)
    if (r && r.records && r.records.length > 0) {
      const exact = r.records.find(p => p.productCode === kw || p.barcode === kw)
      const p = exact || r.records[0]
      if (list.value.find(x => x.productId === p.id)) {
        toast('已在列表中: ' + p.productName)
        return
      }
      // 调详情拿账面数 (用 inv_stock 当前快照)
      let bookQty = 0
      try {
        const snap = (await api.stockSnapshot(warehouseId.value)) || []
        const match = snap.find(s => s.productId === p.id)
        if (match) bookQty = Number(match.bookQty || 0)
      } catch { /* 拿不到账面默认 0 */ }
      list.value.push({
        _key: Date.now() + Math.random(),
        productId: p.id,
        productCode: p.productCode,
        productName: p.productName,
        spec: p.spec,
        bookQty,
        actualQty: bookQty,  // 默认实盘 = 账面
        remark: ''
      })
      if (!exact && r.records.length > 1) {
        toast('找到 ' + r.records.length + ' 个商品, 添加第一个')
      }
    } else {
      toast('商品未找到: ' + kw)
    }
  } catch (e) {
    toast('查询失败: ' + (e.msg || e.message || '网络错误'))
  }
}

function onScan() {
  if (!warehouseId.value) { toast('请先选择仓库'); return }
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

async function onLoadSnapshot() {
  if (!warehouseId.value) {
    toast('请先选择仓库')
    return
  }
  try {
    const snap = (await api.stockSnapshot(warehouseId.value)) || []
    if (snap.length === 0) {
      toast('该仓库暂无库存')
      return
    }
    const existing = new Set(list.value.map(x => x.productId))
    let added = 0
    for (const s of snap) {
      if (existing.has(s.productId)) continue
      list.value.push({
        _key: Date.now() + Math.random(),
        productId: s.productId,
        productCode: s.productCode,
        productName: s.productName,
        spec: s.spec,
        bookQty: Number(s.bookQty || 0),
        actualQty: Number(s.bookQty || 0),
        remark: ''
      })
      added++
    }
    toast(`已预填 ${added} 个商品`)
  } catch (e) {
    toast('预填失败: ' + (e.msg || e.message || '网络错误'))
  }
}

function onClear() {
  if (typeof uni !== 'undefined' && uni.showModal) {
    uni.showModal({
      title: '清空',
      content: '确定清空盘点列表?',
      success: r => { if (r.confirm) list.value = [] }
    })
  } else if (confirm('确定清空列表?')) {
    list.value = []
  }
}

async function onSubmit() {
  // v1.0.8+: 真实提交, 生成 DRAFT 盘点单
  if (!warehouseId.value) { toast('请先选择仓库'); return }
  if (list.value.length === 0) { toast('请添加盘点商品'); return }
  // 校验实盘数量
  for (const it of list.value) {
    if (it.actualQty === '' || it.actualQty == null || isNaN(Number(it.actualQty))) {
      toast(`"${it.productName}" 未填实盘数量`)
      return
    }
  }
  // 二次确认
  const ok = await new Promise(resolve => {
    if (typeof uni !== 'undefined' && uni.showModal) {
      uni.showModal({
        title: '提交盘点',
        content: `将提交 ${list.value.length} 项盘点到服务器, 生成 DRAFT 盘点单, 待 PC 端审核.`,
        success: r => resolve(r.confirm)
      })
    } else {
      resolve(confirm(`将提交 ${list.value.length} 项盘点. 确认?`))
    }
  })
  if (!ok) return

  submitting.value = true
  try {
    const payload = {
      warehouseId: warehouseId.value,
      billDate: new Date().toISOString().substring(0, 10),
      remark: remark.value || '',
      items: list.value.map(it => ({
        productId: it.productId,
        actualQty: Number(it.actualQty),
        remark: it.remark || ''
      }))
    }
    const r = await api.invCheckSubmit(payload)
    const billNo = r.billNo || (r.data && r.data.billNo)
    toast(`提交成功, 单号 ${billNo}`)
    // 成功提示 + 清空
    if (typeof uni !== 'undefined' && uni.showModal) {
      uni.showModal({
        title: '提交成功',
        content: `盘点单 ${billNo} 已创建 (DRAFT), 请到 PC 端审核.`,
        showCancel: false,
        success: () => {
          list.value = []
          remark.value = ''
        }
      })
    } else {
      alert(`提交成功, 单号 ${billNo}`)
      list.value = []
      remark.value = ''
    }
  } catch (e) {
    toast('提交失败: ' + (e.msg || e.message || '网络错误'))
  } finally {
    submitting.value = false
  }
}

onUnmounted(() => { stopScan() })

onMounted(() => {
  if (!checkPagePermission('/pages/count/index')) {
    toast('无访问权限')
    setTimeout(() => window.history.back(), 500)
    return
  }
  loadWarehouses()
})
</script>

<style scoped>
.container { padding: 12px; background: #f5f5f5; min-height: 100vh; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.title { font-size: 15px; font-weight: bold; display: block; margin-bottom: 4px; }
.muted { color: #999; font-size: 12px; }
.form-item { margin: 4px 0; }
.label { display: block; font-size: 11px; color: #999; margin-bottom: 2px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.picker { padding: 10px 12px; background: #f5f7fa; border-radius: 4px; font-size: 14px; }
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 12px; box-sizing: border-box; font-size: 14px; }
.input:focus { border-color: #1e6091; outline: none; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; }
.btn:disabled { background: #c0c4cc; }
.btn-sm { background: #1e6091; color: #fff; padding: 6px 12px; border-radius: 4px; border: none; cursor: pointer; font-size: 13px; }
.btn-block { width: 100%; }
.empty { text-align: center; color: #999; padding: 40px; font-size: 13px; }
.summary { text-align: center; font-size: 13px; color: #666; padding: 10px; background: #fafafa; border-radius: 4px; margin-top: 10px; }
</style>
