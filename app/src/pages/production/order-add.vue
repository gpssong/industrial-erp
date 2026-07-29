<template>
  <view class="container">
    <!-- Loading 遮罩 -->
    <view v-if="loading" class="loading-overlay">
      <view class="loading-mask"><text class="loading-text">加载中...</text></view>
    </view>

    <view class="card">
      <text class="title">{{ form.id ? '编辑生产单' : '新增生产单' }}</text>
    </view>

    <!-- 成品选择 -->
    <view class="card">
      <text class="section-title">成品</text>

      <view class="form-item">
        <text class="label">选择成品 *</text>
        <view class="picker" @click="showProductPicker = true">
          <text :class="{ placeholder: !form.productId }" style="color:#333">{{ currentProductName || '请选择成品' }}</text>
        </view>
      </view>

      <!-- 成品自动带出的信息 (只读) -->
      <view v-if="form.productName" class="info-grid">
        <view class="info-item">
          <text class="info-label">BOM</text>
          <text class="info-value">{{ form.bomName || '-' }}</text>
        </view>
        <view class="info-item">
          <text class="info-label">规格</text>
          <text class="info-value">{{ form.spec || '-' }}</text>
        </view>
        <view class="info-item">
          <text class="info-label">长度</text>
          <text class="info-value">{{ form.thickness || '-' }}</text>
        </view>
        <view class="info-item">
          <text class="info-label">宽度</text>
          <text class="info-value">{{ form.width || '-' }}</text>
        </view>
        <view class="info-item">
          <text class="info-label">厚度</text>
          <text class="info-value">{{ form.density || '-' }}</text>
        </view>
        <view class="info-item">
          <text class="info-label">克重</text>
          <text class="info-value">{{ form.gramWeight || '-' }}</text>
        </view>
        <view class="info-item">
          <text class="info-label">材质</text>
          <text class="info-value">{{ form.material || '-' }}</text>
        </view>
      </view>
    </view>

    <!-- 计划 -->
    <view class="card">
      <text class="section-title">计划信息</text>

      <view class="form-item">
        <text class="label">计划数量 *</text>
        <input class="input" v-model.number="form.planQty" type="digit" placeholder="计划数量" />
      </view>
      <view class="form-item">
        <text class="label">损耗率 (%)</text>
        <input class="input" v-model.number="form.lossRate" type="digit" placeholder="损耗率, 默认0" />
      </view>
    </view>

    <!-- 生产安排 -->
    <view class="card">
      <text class="section-title">生产安排</text>

      <view class="form-item">
        <text class="label">车间</text>
        <input class="input" v-model="form.workshop" placeholder="车间名称" />
      </view>
      <view class="form-item">
        <text class="label">仓库</text>
        <view class="picker" @click="showWarehousePicker = true">
          <text :class="{ placeholder: !form.warehouseId }" style="color:#333">{{ currentWarehouseName || '请选择仓库' }}</text>
        </view>
      </view>
      <view class="form-item">
        <text class="label">负责人</text>
        <input class="input" v-model="form.leader" placeholder="负责人" />
      </view>
      <view class="form-item">
        <text class="label">开工日期</text>
        <input class="input" v-model="form.startDate" placeholder="YYYY-MM-DD" @focus="showDatePicker = true" />
      </view>
      <view class="form-item">
        <text class="label">结束日期</text>
        <input class="input" v-model="form.endDate" placeholder="YYYY-MM-DD" @focus="showEndDatePicker = true" />
      </view>
    </view>

    <!-- 备注 -->
    <view class="card">
      <text class="section-title">备注</text>
      <view class="form-item">
        <textarea class="textarea" v-model="form.remark" placeholder="生产备注" maxlength="500" />
      </view>
    </view>

    <!-- 提交按钮 -->
    <view style="padding: 16px;">
      <button class="btn btn-block" @click="onSubmit" :loading="submitting">确定</button>
      <!-- v1.1.11+: 编辑 DRAFT 状态时显示删除按钮 -->
      <button v-if="form.id && form.billStatus === 'DRAFT'" class="btn btn-danger btn-block" style="margin-top:10px" @click="onDelete">删除生产单</button>
    </view>

    <!-- 成品选择弹窗 -->
    <view v-if="showProductPicker" class="mask" @click="showProductPicker = false">
      <view class="modal" @click.stop>
        <text class="modal-title">选择成品</text>
        <view v-if="!productList.length" class="empty">正在加载商品列表...</view>
        <view v-for="(p, i) in productList" :key="p.id" class="modal-item" @click="onProductSelect(i)">
          <text>{{ p.productName }} ({{ p.productCode }})</text>
        </view>
        <view class="modal-close" @click="showProductPicker = false">取消</view>
      </view>
    </view>

    <!-- 仓库选择弹窗 -->
    <view v-if="showWarehousePicker" class="mask" @click="showWarehousePicker = false">
      <view class="modal" @click.stop>
        <text class="modal-title">选择仓库</text>
        <view v-if="!warehouseList.length" class="empty">正在加载仓库列表...</view>
        <view v-for="(w, i) in warehouseList" :key="w.id" class="modal-item" @click="onWarehouseSelect(i)">
          <text>{{ w.warehouseName }}</text>
        </view>
        <view class="modal-close" @click="showWarehousePicker = false">取消</view>
      </view>
    </view>

    <!-- 日期选择 -->
    <uni-datetime-picker
      v-if="showDatePicker"
      :value="form.startDate"
      @change="v => form.startDate = v"
      @cancel="showDatePicker = false"
      type="date"
    />
    <uni-datetime-picker
      v-if="showEndDatePicker"
      :value="form.endDate"
      @change="v => form.endDate = v"
      @cancel="showEndDatePicker = false"
      type="date"
    />
  </view>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import api from '../../api/index.js'
import { onLoad } from '@dcloudio/uni-app'

const loading = ref(false)
const submitting = ref(false)
const showProductPicker = ref(false)
const showWarehousePicker = ref(false)
const showDatePicker = ref(false)
const showEndDatePicker = ref(false)
const productList = ref([])
const warehouseList = ref([])

const form = reactive({
  id: null,
  billStatus: '',  // v1.1.11+: 编辑时载入, 用于判断 DRAFT 显隐删除按钮
  productId: null,
  productName: '',
  productCode: '',
  bomName: '',
  spec: '',
  thickness: null,
  width: null,
  density: null,
  gramWeight: null,
  material: '',
  planQty: 1,
  lossRate: 0,
  workshop: '',
  warehouseId: null,
  leader: '',
  startDate: '',
  endDate: '',
  remark: ''
})

const currentProductName = computed(() => form.productName || '')
const currentWarehouseName = computed(() => {
  if (!form.warehouseId) return ''
  const w = warehouseList.value.find(x => x.id === form.warehouseId)
  return w ? w.warehouseName : ''
})

let orderId = null

onLoad((options) => {
  if (options.id) {
    orderId = Number(options.id)
    loadOrder(orderId)
  }
  loadProductList()
  loadWarehouseList()
})

function toast(msg) {
  uni.showToast({ title: msg, icon: 'none' })
}

async function loadOrder(id) {
  loading.value = true
  try {
    const r = await api.prdOrderDetail(id)
    if (r) {
      form.id = r.id
      form.billStatus = r.billStatus || 'DRAFT'
      form.productId = r.productId
      form.productName = r.productName || ''
      form.productCode = r.productCode || ''
      form.bomName = r.bomName || ''
      form.spec = r.spec || ''
      form.thickness = r.thickness
      form.width = r.width
      form.density = r.density
      form.gramWeight = r.gramWeight
      form.material = r.material || ''
      form.planQty = r.planQty || 1
      form.lossRate = r.lossRate || 0
      form.workshop = r.workshop || ''
      form.warehouseId = r.warehouseId
      form.leader = r.leader || ''
      form.startDate = r.startDate || ''
      form.endDate = r.endDate || ''
      form.remark = r.remark || ''
    }
  } catch (e) {
    toast('加载失败: ' + (e.msg || e.message || '网络错误'))
  } finally {
    loading.value = false
  }
}

async function loadProductList() {
  loading.value = true
  try {
    // P1-9: 后端 maxLimit=200, 传 9999 会被截断. 改 200 + 用户搜索时再远程拉
    const r = await api.productPage({ pageNum: 1, pageSize: 200 })
    // api.productPage 返回的是 PageResult 对象 {records, total}, 需要取 records 数组
    productList.value = (r && r.records) || (Array.isArray(r) ? r : [])
  } catch (e) {
    productList.value = []
  } finally {
    loading.value = false
  }
}

async function loadWarehouseList() {
  loading.value = true
  try {
    warehouseList.value = await api.warehouseList()
  } catch (e) {
    warehouseList.value = []
  } finally {
    loading.value = false
  }
}

function onProductSelect(idx) {
  const p = productList.value[idx]
  if (!p) return
  form.productId = p.id
  form.productName = p.productName
  form.productCode = p.productCode
  form.spec = p.spec || ''
  form.thickness = p.thickness
  form.width = p.width
  form.density = p.density
  form.gramWeight = p.gramWeight
  form.material = p.material || ''
  form.bomId = p.bomId || null
  showProductPicker.value = false
}

function onWarehouseSelect(idx) {
  const w = warehouseList.value[idx]
  if (!w) return
  form.warehouseId = w.id
  showWarehousePicker.value = false
}

async function onSubmit() {
  if (!form.productId) { toast('请选择成品'); return }
  if (!form.planQty || form.planQty <= 0) { toast('请填写计划数量'); return }

  submitting.value = true
  try {
    const payload = { ...form }
    let newOrderId = null
    if (payload.id) {
      await api.prdOrderUpdate(payload.id, payload)
      toast('修改成功')
      newOrderId = payload.id
    } else {
      // v1.1.9+: 后端 add() 返回新生成的单据 ID, 用于飞鹅打印
      newOrderId = await api.prdOrderAdd(payload)
      toast('新增成功')
    }

    // v1.1.9+: 提交成功后, 弹飞鹅云打印提示 (仅新增场景, 编辑后打印用列表页"飞鹅打印"按钮)
    // 权限检查: erp_permissions 含 production:order:feie-print
    if (!payload.id && newOrderId && hasFeiePrintPerm()) {
      const ok = await askFeiePrint()
      if (ok) {
        await doFeiePrint(newOrderId)
      }
    }

    const pages = getCurrentPages()
    if (pages.length > 1) {
      uni.navigateBack()
    } else {
      uni.reLaunch({ url: '/pages/dashboard/index' })
    }
  } catch (e) {
    toast('提交失败: ' + (e.msg || e.message || '网络错误'))
  } finally {
    submitting.value = false
  }
}

// v1.1.11+: 编辑 DRAFT 状态时删除生产单
async function onDelete() {
  if (!form.id) return
  if (typeof uni === 'undefined' || !uni.showModal) return
  const ok = await new Promise((resolve) => {
    uni.showModal({
      title: '删除',
      content: '确定删除该生产单? 此操作不可恢复',
      success: (res) => resolve(res.confirm),
      fail: () => resolve(false)
    })
  })
  if (!ok) return
  try {
    await api.prdOrderDelete(form.id)
    uni.showToast({ title: '已删除', icon: 'success' })
    setTimeout(() => uni.reLaunch({ url: '/pages/production/order-list' }), 800)
  } catch (e) {
    uni.showToast({ title: e.message || '删除失败', icon: 'none' })
  }
}

// v1.1.9+: 检查是否拥有飞鹅云打印权限 (production:order:feie-print)
function hasFeiePrintPerm() {
  try {
    const perms = JSON.parse(localStorage.getItem('erp_permissions') || '[]')
    return Array.isArray(perms) && perms.includes('production:order:feie-print')
  } catch { return false }
}

// v1.1.9+: 弹窗询问是否飞鹅云打印
function askFeiePrint() {
  return new Promise((resolve) => {
    if (typeof uni === 'undefined' || typeof uni.showModal !== 'function') {
      resolve(false)
      return
    }
    uni.showModal({
      title: '飞鹅云打印',
      content: '生产单已保存, 是否立即发送到飞鹅云打印机?',
      confirmText: '打印',
      cancelText: '暂不',
      success: (res) => resolve(res.confirm),
      fail: () => resolve(false)
    })
  })
}

// v1.1.9+: 实际调用飞鹅云打印 (POST /api/feie/print/PRD_ORDER/{id})
async function doFeiePrint(orderId) {
  if (!orderId) return
  uni.showLoading({ title: '正在发送打印...', mask: true })
  try {
    await api.feiePrint('PRD_ORDER', orderId)
    uni.hideLoading()
    uni.showToast({ title: '打印已发送', icon: 'success' })
  } catch (e) {
    uni.hideLoading()
    // 失败不阻塞主流程, 已在 api.request 的 success 分支统一提示过, 这里只记日志
    console.warn('[feie] 飞鹅打印失败:', e)
  }
}
</script>

<style scoped>
.container { padding: 12px; background: #f5f5f5; min-height: 100vh; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.section-title { font-size: 14px; font-weight: bold; display: block; margin-bottom: 8px; color: #333; border-left: 3px solid #1e6091; padding-left: 8px; }
.form-item { margin: 8px 0; }
.label { display: block; font-size: 12px; color: #999; margin-bottom: 4px; }
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.textarea { width: 100%; height: 80px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 8px 10px; box-sizing: border-box; font-size: 14px; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; width: 100%; }
.btn-block { width: 100%; }
.title { font-size: 15px; font-weight: bold; display: block; margin-bottom: 4px; }
.muted { color: #999; font-size: 11px; }
.picker { height: 36px; line-height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; background: #fff; }
.placeholder { color: #ccc; }

.info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 8px; }
.info-item { background: #f8f9fa; border-radius: 4px; padding: 6px 8px; }
.info-label { display: block; font-size: 10px; color: #999; }
.info-value { display: block; font-size: 13px; color: #333; font-weight: 500; }

.mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); z-index: 999; display: flex; align-items: center; justify-content: center; }
.modal { background: #fff; border-radius: 12px; width: 80%; max-width: 360px; max-height: 70vh; overflow-y: auto; padding: 16px; }
.modal-title { font-size: 16px; font-weight: bold; margin-bottom: 12px; display: block; }
.modal-item { padding: 12px 8px; border-bottom: 1px solid #eee; font-size: 14px; }
.modal-item:active { background: #f5f5f5; }
.modal-close { padding: 12px; text-align: center; color: #c0392b; margin-top: 8px; border-top: 1px solid #eee; }
.empty { text-align: center; color: #999; padding: 20px; font-size: 13px; }

.loading-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; z-index: 9999; display: flex; align-items: center; justify-content: center; }
.loading-mask { background: rgba(0,0,0,0.4); border-radius: 8px; padding: 20px 30px; }
.loading-text { color: #fff; font-size: 14px; }
</style>
