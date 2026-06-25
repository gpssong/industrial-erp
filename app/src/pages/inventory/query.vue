<template>
  <div class="container">
    <div class="card search-bar">
      <div class="search-row">
        <input class="input" v-model="keyword" placeholder="扫码或输入编码/名称" @confirm="loadData" />
        <button class="btn-scan" @click="onScan" title="扫码">📷</button>
        <button class="btn-search" @click="loadData">查询</button>
      </div>
    </div>
    <div class="card" v-for="item in list" :key="item.id" @click="onDetail(item)">
      <div class="row">
        <div>
          <div style="font-weight:bold">{{ item.productName }}</div>
          <div class="muted" style="display:block">{{ item.productCode }} / {{ item.spec }}</div>
        </div>
        <div style="text-align:right">
          <div style="color:#1e6091;font-weight:bold;font-size:16px">{{ item.qty }}</div>
          <div class="muted" style="display:block">{{ item.unitName }}</div>
        </div>
      </div>
      <div class="row" style="margin-top:6px">
        <span class="muted">{{ item.warehouseName }} · {{ item.batchNo || '无批次' }}</span>
        <span class="muted">均价 ¥{{ item.avgCost }}</span>
      </div>
    </div>
    <div v-if="!list.length" class="empty">暂无库存数据</div>

    <!-- 扫码弹窗 -->
    <div v-if="showScanner" class="scanner-mask">
      <div class="scanner-box">
        <div class="scanner-header">
          <span>扫描商品条码</span>
          <button class="btn-close" @click="closeScanner">✕</button>
        </div>
        <div id="qr-reader" style="width:100%"></div>
        <div class="scanner-tip">将条码对准摄像头</div>
      </div>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import api, { isH5 } from '../../api/index.js'

const keyword = ref('')
const list = ref([])
const showScanner = ref(false)
let html5QrCode = null

async function loadData() {
  try {
    const r = await api.stockPage({ pageNum: 1, pageSize: 50, keyword: keyword.value })
    list.value = (r && r.records) || []
  } catch (e) {
    list.value = []
  }
}

function onDetail(item) {
  alert(`${item.productName}\n库存: ${item.qty} ${item.unitName}\n均价: ¥${item.avgCost}\n总成本: ¥${item.totalCost}`)
}

async function onScan() {
  if (isH5()) {
    await openH5Scanner()
    return
  }
  if (typeof uni !== 'undefined' && uni.scanCode) {
    uni.scanCode({
      success: (res) => { keyword.value = res.result; loadData() },
      fail: () => {}
    })
  }
}

async function openH5Scanner() {
  showScanner.value = true
  await nextTick()
  try {
    const { Html5Qrcode } = await import('html5-qrcode')
    html5QrCode = new Html5Qrcode('qr-reader')
    await html5QrCode.start(
      { facingMode: 'environment' },
      { fps: 10, qrbox: { width: 250, height: 250 }, aspectRatio: 1.0 },
      async (decodedText) => {
        keyword.value = decodedText
        await closeScanner()
        await loadData()
      },
      () => {}
    )
  } catch (err) {
    console.error('摄像头启动失败:', err)
    await closeScanner()
    const c = prompt('摄像头不可用,请输入条码:')
    if (c) { keyword.value = c; loadData() }
  }
}

async function closeScanner() {
  showScanner.value = false
  if (html5QrCode) {
    try { await html5QrCode.stop() } catch (e) {}
    html5QrCode = null
  }
}

onMounted(loadData)
onUnmounted(() => { closeScanner() })
</script>
<style scoped>
.container { padding: 12px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.search-bar { padding: 10px; }
.search-row { display: flex; gap: 6px; align-items: center; }
.input { flex: 1; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.input:focus { border-color: #1e6091; outline: none; }
.btn-scan { background: #27ae60; color: #fff; border: none; border-radius: 4px; width: 40px; height: 36px; cursor: pointer; font-size: 16px; }
.btn-scan:hover { background: #2ecc71; }
.btn-search { background: #1e6091; color: #fff; border: none; border-radius: 4px; padding: 0 14px; height: 36px; cursor: pointer; font-size: 14px; }
.btn-search:hover { background: #2980b9; }
.row { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #999; font-size: 12px; }
.empty { text-align: center; color: #999; padding: 40px; }
.scanner-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.8); z-index: 9999; display: flex; align-items: center; justify-content: center; }
.scanner-box { background: #fff; border-radius: 12px; padding: 16px; width: 90%; max-width: 360px; }
.scanner-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; font-size: 16px; font-weight: bold; }
.btn-close { background: none; border: none; font-size: 20px; cursor: pointer; color: #999; }
.scanner-tip { text-align: center; color: #666; font-size: 13px; margin-top: 10px; }
</style>
