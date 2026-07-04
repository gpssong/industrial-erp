<template>
  <div class="container">
    <div class="card">
      <div class="title">📤 扫码出库 (销售)</div>
      <div class="row" style="margin: 12px 0">
        <span>扫到的条码</span>
        <span style="font-weight:bold">{{ code || '(等待扫码)' }}</span>
      </div>
      <button class="btn btn-block" @click="onScan">📷 扫一扫</button>
      <button class="btn btn-block btn-outline" style="margin-top:8px" @click="onManualInput">✏️ 手动输入</button>
    </div>

    <div class="card" v-if="product">
      <div class="form-item">
        <label class="label">客户 <span style="color:#c0392b">*</span></label>
        <select class="input" v-model="customerId">
          <option value="">请选择客户</option>
          <option v-for="c in customers" :key="c.id" :value="c.id">{{ c.customerName }}</option>
        </select>
      </div>
      <div style="font-size:18px;font-weight:bold">{{ product.productName }}</div>
      <div class="row" style="margin: 6px 0">
        <span class="muted">当前库存</span>
        <span style="color:#1e6091">{{ product.qty }} {{ product.unitName }}</span>
      </div>
      <div class="form-item"><label class="label">销售数量</label><input class="input" type="number" v-model.number="qty" /></div>
      <div class="form-item"><label class="label">销售单价</label><input class="input" type="number" step="0.01" v-model.number="price" /></div>
      <button class="btn btn-block" @click="onSubmit">确认出库</button>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import api from '../../api/index.js'
import { doScan, stopScan } from '../../utils/scan.js'
import { checkPagePermission } from '../../utils/permission.js'

const code = ref('')
const product = ref(null)
const qty = ref(1)
const price = ref(0)
const customers = ref([])
const customerId = ref('')

function toast(msg) { alert(msg) }

onMounted(async () => {
  // 权限校验
  if (!checkPagePermission('/pages/scan/out')) {
    toast('无访问权限')
    setTimeout(() => window.history.back(), 500)
    return
  }
  try { customers.value = await api.customerList() || [] } catch (e) { customers.value = [] }
})

function onScan() {
  doScan({
    onResult: (text) => { code.value = text; onSearch() },
    onCancel: () => {},
    onError: (err) => { toast('扫码失败: ' + (err.message || err)) }
  })
}

function onManualInput() {
  const c = prompt('请输入商品编码或条码:')
  if (c) { code.value = c; onSearch() }
}

async function onSearch() {
  if (!code.value) return
  try {
    const r = await api.productPage({ pageNum: 1, pageSize: 10, keyword: code.value })
    if (r && r.records && r.records.length > 0) {
      const exact = r.records.find(p => p.productCode === code.value || p.barcode === code.value)
      const found = exact || r.records[0]
      product.value = found
      price.value = found.salesPrice || 0
      if (!exact && r.records.length > 1) {
        toast('找到 ' + r.records.length + ' 个商品, 显示第一个: ' + found.productName)
      }
    } else {
      product.value = null
      toast('商品未找到: ' + code.value)
    }
  } catch (e) {
    toast('查询失败: ' + (e.msg || e.message || '网络错误'))
  }
}

async function onSubmit() {
  if (!product.value) return
  if (!customerId.value) return toast('请选择客户')
  const detail = {
    productId: product.value.productId, productCode: product.value.productCode,
    productName: product.value.productName, spec: product.value.spec,
    unitName: product.value.unitName, qty: +qty.value, price: +price.value, taxRate: 13
  }
  detail.amount = detail.qty * detail.price
  detail.taxAmount = detail.amount * 0.13
  detail.amountTax = detail.amount + detail.taxAmount
  const bill = {
    billDate: new Date().toISOString().substring(0, 10),
    customerId: +customerId.value, warehouseId: product.value.warehouseId || 1,
    billType: 'NORMAL', billStatus: 'DRAFT', details: [detail]
  }
  try {
    await api.salesDeliveryAdd(bill)
    toast('出库成功!')
    setTimeout(() => window.history.back(), 800)
  } catch (e) {
    toast('出库失败: ' + (e.msg || e.message || '网络错误'))
  }
}

onUnmounted(() => { stopScan() })
</script>
<style scoped>
.container { padding: 12px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.title { font-size: 16px; font-weight: bold; color: #333; margin-bottom: 8px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.form-item { margin: 8px 0; }
.label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; background: #fff; }
.input:focus { border-color: #1e6091; outline: none; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; width: 100%; }
.btn:hover { background: #2980b9; }
.btn-outline { background: transparent; color: #1e6091; border: 1px solid #1e6091; }
.muted { color: #999; font-size: 12px; }
</style>