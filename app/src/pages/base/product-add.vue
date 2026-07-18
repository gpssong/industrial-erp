<template>
  <view class="container">
    <!-- Loading 遮罩 -->
    <view v-if="loading" class="loading-overlay">
      <view class="loading-mask"><text class="loading-text">加载中...</text></view>
    </view>

    <view class="card">
      <text class="title">{{ form.id ? '编辑商品' : '新增商品' }}</text>
    </view>

    <!-- 基本信息 -->
    <view class="card">
      <text class="section-title">基本信息</text>

      <view class="form-item">
        <text class="label">商品编码 *</text>
        <input class="input" v-model="form.productCode" placeholder="请输入商品编码" />
      </view>
      <view class="form-item">
        <text class="label">商品名称 *</text>
        <input class="input" v-model="form.productName" placeholder="请输入商品名称" />
      </view>
      <view class="form-item">
        <text class="label">规格</text>
        <input class="input" v-model="form.spec" placeholder="如: 28*36*0.16" />
      </view>
      <view class="form-item">
        <text class="label">型号</text>
        <input class="input" v-model="form.model" placeholder="请输入型号" />
      </view>
      <view class="form-item">
        <text class="label">材质</text>
        <input class="input" v-model="form.material" placeholder="如: PE" />
      </view>
      <view class="form-item">
        <text class="label">色号</text>
        <input class="input" v-model="form.colorNo" placeholder="色号" />
      </view>
      <view class="form-item">
        <text class="label">条码</text>
        <input class="input" v-model="form.barcode" placeholder="商品条码" />
      </view>
    </view>

    <!-- 规格参数 -->
    <view class="card">
      <text class="section-title">规格参数</text>

      <view class="form-item">
        <text class="label">长度 (mm)</text>
        <input class="input" v-model.number="form.thickness" type="digit" placeholder="长度" />
      </view>
      <view class="form-item">
        <text class="label">宽度 (mm)</text>
        <input class="input" v-model.number="form.width" type="digit" placeholder="宽度" />
      </view>
      <view class="form-item">
        <text class="label">厚度 (密度)</text>
        <input class="input" v-model.number="form.density" type="digit" placeholder="厚度/密度" />
      </view>
      <view class="form-item">
        <text class="label">克重 (g/m²)</text>
        <input class="input" v-model.number="form.gramWeight" type="digit" placeholder="克重" />
      </view>
    </view>

    <!-- 价格 -->
    <view class="card">
      <text class="section-title">价格</text>

      <view class="form-item">
        <text class="label">销售价格</text>
        <input class="input" v-model.number="form.salesPrice" type="digit" placeholder="销售价格" />
      </view>
      <view class="form-item">
        <text class="label">采购价格</text>
        <input class="input" v-model.number="form.purchasePrice" type="digit" placeholder="采购价格" />
      </view>
      <view class="form-item">
        <text class="label">成本价格</text>
        <input class="input" v-model.number="form.costPrice" type="digit" placeholder="成本价格" />
      </view>
      <view class="form-item">
        <text class="label">税率 (%)</text>
        <input class="input" v-model.number="form.taxRate" type="digit" placeholder="税率, 默认13" />
      </view>
      <view class="form-item">
        <text class="label">安全库存</text>
        <input class="input" v-model.number="form.safetyStock" type="number" placeholder="安全库存" />
      </view>
    </view>

    <!-- 备注 -->
    <view class="card">
      <text class="section-title">备注</text>
      <view class="form-item">
        <textarea class="textarea" v-model="form.remark" placeholder="商品备注 (最多500字)" maxlength="500" />
      </view>
    </view>

    <!-- 提交按钮 -->
    <view style="padding: 16px;">
      <button class="btn btn-block" @click="onSubmit" :loading="submitting">确定</button>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import api from '../../api/index.js'
import { onLoad, onUnload } from '@dcloudio/uni-app'

const loading = ref(false)
const submitting = ref(false)

const form = reactive({
  id: null,
  productCode: '',
  productName: '',
  spec: '',
  model: '',
  material: '',
  thickness: null,
  width: null,
  density: null,
  gramWeight: null,
  colorNo: '',
  barcode: '',
  salesPrice: 0,
  purchasePrice: 0,
  costPrice: 0,
  taxRate: 13.00,
  safetyStock: 0,
  remark: ''
})

let productId = null // 编辑时的 ID, 从路由参数获取

onLoad((options) => {
  if (options.id) {
    productId = Number(options.id)
    loadProduct(productId)
  }
})

onUnload(() => {
  productId = null
})

function toast(msg) {
  uni.showToast({ title: msg, icon: 'none' })
}

async function loadProduct(id) {
  loading.value = true
  try {
    const r = await api.productDetail(id)
    if (r) {
      form.id = r.id
      form.productCode = r.productCode || ''
      form.productName = r.productName || ''
      form.spec = r.spec || ''
      form.model = r.model || ''
      form.material = r.material || ''
      form.thickness = r.thickness
      form.width = r.width
      form.density = r.density
      form.gramWeight = r.gramWeight
      form.colorNo = r.colorNo || ''
      form.barcode = r.barcode || ''
      form.salesPrice = r.salesPrice || 0
      form.purchasePrice = r.purchasePrice || 0
      form.costPrice = r.costPrice || 0
      form.taxRate = r.taxRate || 13
      form.safetyStock = r.safetyStock || 0
      form.remark = r.remark || ''
    }
  } catch (e) {
    toast('加载失败: ' + (e.msg || e.message || '网络错误'))
  } finally {
    loading.value = false
  }
}

async function onSubmit() {
  // 校验
  if (!form.productCode) { toast('请输入商品编码'); return }
  if (!form.productName) { toast('请输入商品名称'); return }

  submitting.value = true
  try {
    const payload = { ...form }
    // 空字符串转 null, 避免覆盖后端值
    if (!payload.spec) payload.spec = ''
    if (!payload.model) payload.model = ''
    if (!payload.material) payload.material = ''
    if (!payload.colorNo) payload.colorNo = ''
    if (!payload.barcode) payload.barcode = ''
    if (!payload.remark) payload.remark = ''

    if (payload.id) {
      await api.request({ url: '/base/product/' + payload.id, method: 'PUT', data: payload })
      toast('修改成功')
    } else {
      await api.request({ url: '/base/product', method: 'POST', data: payload })
      toast('新增成功')
    }
    // 返回上一页
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

/* Loading overlay */
.loading-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; z-index: 9999; display: flex; align-items: center; justify-content: center; }
.loading-mask { background: rgba(0,0,0,0.4); border-radius: 8px; padding: 20px 30px; }
.loading-text { color: #fff; font-size: 14px; }
</style>
