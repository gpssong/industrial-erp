<template>
  <view class="container">
    <!-- Loading 遮罩 -->
    <view v-if="loading" class="loading-overlay">
      <view class="loading-mask"><text class="loading-text">加载中...</text></view>
    </view>

    <view class="card">
      <text class="title">新增商品</text>
      <text class="muted">建好的商品在 PC 端「基础数据 → 商品管理」可继续编辑单位、配方与照片</text>
    </view>

    <!-- 无权限 (工作台入口按 perm 过滤, 深链直达时在这里兜住) -->
    <view v-if="!canAdd" class="card">
      <text class="section-title">无新建权限</text>
      <text class="muted">当前账号没有「新增商品」权限 (base:product:add)，请联系管理员在 PC 端「角色管理 → App 端菜单权限」勾选。</text>
      <view style="margin-top: 12px;">
        <button class="btn-plain" @click="goBack">返回工作台</button>
      </view>
    </view>

    <template v-else>
      <!-- 基本信息 -->
      <view class="card">
        <text class="section-title">基本信息</text>

        <view class="form-item">
          <text class="label">商品编码 *</text>
          <input class="input" v-model="form.productCode" placeholder="如: BD-3250" maxlength="50" />
        </view>
        <view class="form-item">
          <text class="label">商品名称 *</text>
          <input class="input" v-model="form.productName" placeholder="如: 背心袋 32*50" maxlength="200" />
        </view>
        <view class="form-item">
          <text class="label">类型 *</text>
          <picker mode="selector" :range="typeLabels" :value="typeIndex" @change="onPickType">
            <view class="input picker-val">{{ typeLabels[typeIndex] }}</view>
          </picker>
        </view>
        <view class="form-item">
          <text class="label">规格</text>
          <input class="input" v-model="form.spec" placeholder="如: 28*36*0.16" maxlength="100" />
        </view>
        <view class="form-item">
          <text class="label">型号</text>
          <input class="input" v-model="form.model" placeholder="请输入型号" maxlength="100" />
        </view>
        <view class="form-item">
          <text class="label">材质</text>
          <input class="input" v-model="form.material" placeholder="如: PE" maxlength="100" />
        </view>
        <view class="form-item">
          <text class="label">色号</text>
          <input class="input" v-model="form.colorNo" placeholder="色号" maxlength="50" />
        </view>
        <view class="form-item">
          <text class="label">条码</text>
          <view class="row-inline">
            <input class="input flex1" v-model="form.barcode" placeholder="商品条码 (可扫码)" maxlength="64" />
            <button class="btn-scan" @click="onScanBarcode">📷</button>
          </view>
          <text class="hint">条码全库唯一，重复时后端会提示已被哪个商品占用</text>
        </view>
      </view>

      <!-- 单位与价格 -->
      <view class="card">
        <text class="section-title">单位与价格</text>

        <view class="form-item">
          <text class="label">主单位 *</text>
          <picker v-if="units.length" mode="selector" :range="unitNames" :value="unitIndex" @change="onPickUnit">
            <view class="input picker-val" :class="{ placeholder: unitIndex < 0 }">
              {{ unitIndex >= 0 ? unitNames[unitIndex] : '请选择主单位' }}
            </view>
          </picker>
          <view v-else class="input picker-val placeholder">
            {{ unitsFailed ? '单位档案加载失败，请下拉重试' : '暂无可选单位' }}
          </view>
          <text v-if="unitsFailed" class="hint warn" @click="loadUnits">加载失败，点击重试</text>
          <text v-else class="hint">主单位决定库存与出入库的计量口径，1 {{ unitIndex >= 0 ? unitNames[unitIndex] : '单位' }} = 1</text>
        </view>
        <view class="form-item">
          <text class="label">销售价格</text>
          <input class="input" v-model="form.salesPrice" type="digit" placeholder="0.00" />
        </view>
        <view class="form-item">
          <text class="label">采购价格</text>
          <input class="input" v-model="form.purchasePrice" type="digit" placeholder="0.00" />
        </view>
        <view class="form-item">
          <text class="label">成本价格</text>
          <input class="input" v-model="form.costPrice" type="digit" placeholder="0.00" />
        </view>
        <view class="form-item">
          <text class="label">税率 (%)</text>
          <input class="input" v-model="form.taxRate" type="digit" placeholder="默认 13" />
        </view>
        <view class="form-item">
          <text class="label">安全库存</text>
          <input class="input" v-model="form.safetyStock" type="digit" placeholder="低于该值工作台预警" />
          <text class="hint">这里填的是预警线，不是实际库存；实际库存由「原料入库 / 成品入库 / 盘点」产生</text>
        </view>
      </view>

      <!-- 规格参数 -->
      <view class="card">
        <text class="section-title">规格参数</text>

        <view class="form-item">
          <text class="label">长度</text>
          <input class="input" v-model="form.thickness" type="digit" placeholder="长度" />
        </view>
        <view class="form-item">
          <text class="label">宽度</text>
          <input class="input" v-model="form.width" type="digit" placeholder="宽度" />
        </view>
        <view class="form-item">
          <text class="label">厚度</text>
          <input class="input" v-model="form.density" type="digit" placeholder="厚度" />
        </view>
        <view class="form-item">
          <text class="label">克重 (g/个)</text>
          <input class="input" v-model="form.gramWeight" type="digit" placeholder="单件净重" />
          <text class="hint">生产单总重量 kg = 数量 × 克重 ÷ 1000，领料量也按这个算</text>
        </view>
      </view>

      <!-- 食品接触合规 -->
      <view class="card">
        <text class="section-title">食品接触合规</text>

        <view class="form-item">
          <view class="row-inline">
            <text class="label flex1">食品级 (XK 受控)</text>
            <switch :checked="form.isFoodGrade === 1" color="#1e6091" @change="onToggleFood" />
          </view>
          <text class="hint">亮=食品接触用原料/成品，走 XK 受控流程（来料检验 + 合格供应商名录）；暗=工业品，免检</text>
        </view>

        <template v-if="form.isFoodGrade === 1">
          <view class="form-item">
            <text class="label">食品级用途</text>
            <input class="input" v-model="form.foodUseDesc" placeholder="如 食品接触/食品保鲜袋" maxlength="200" />
          </view>
          <view class="form-item">
            <text class="label">执行标准号</text>
            <input class="input" v-model="form.foodExecStd" placeholder="如 GB 4806.7" maxlength="100" />
          </view>
          <view class="form-item">
            <text class="label">备案编号</text>
            <input class="input" v-model="form.foodFilingNo" placeholder="食品相关产品生产许可证/备案号" maxlength="100" />
          </view>
          <view class="form-item">
            <text class="label">总迁移量限值 (mg/dm²)</text>
            <input class="input" v-model="form.foodMigrationLimit" type="digit" placeholder="可空，按企业标准 Q/ZPC 1-2017 填" />
          </view>
          <view class="form-item">
            <text class="label">保质期 (天)</text>
            <input class="input" v-model="form.shelfLifeDays" type="digit" placeholder="可空" />
          </view>
        </template>
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
        <button class="btn btn-block" @click="onSubmit" :loading="submitting">确定新增</button>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import api from '../../api/index.js'
import { onLoad } from '@dcloudio/uni-app'
import { guardPage, isPermEnabled } from '../../utils/appModuleEnabled.js'
import { isAdmin, getAppPermissions } from '../../utils/permission.js'
import { doScan } from '../../utils/scan.js'

const PERM = 'base:product:add'

const loading = ref(false)
const submitting = ref(false)
const canAdd = ref(false)

const form = reactive({
  productCode: '',
  productName: '',
  productType: 'FG',
  spec: '',
  model: '',
  material: '',
  colorNo: '',
  barcode: '',
  thickness: '',
  width: '',
  density: '',
  gramWeight: '',
  salesPrice: '',
  purchasePrice: '',
  costPrice: '',
  taxRate: '13',
  safetyStock: '',
  isFoodGrade: 0,
  foodUseDesc: '',
  foodExecStd: '',
  foodFilingNo: '',
  foodMigrationLimit: '',
  shelfLifeDays: '',
  remark: ''
})

// 类型与 PC 端 Product.vue 的 PT_LABEL 严格对齐
const TYPES = [
  { value: 'RAW', label: '原材料' },
  { value: 'SEMI', label: '半成品' },
  { value: 'FG', label: '成品' },
  { value: 'GOODS', label: '商品' },
  { value: 'SERVICE', label: '服务' }
]
const typeLabels = TYPES.map(t => t.label)
const typeIndex = computed(() => {
  const i = TYPES.findIndex(t => t.value === form.productType)
  return i < 0 ? 2 : i
})
function onPickType(e) { form.productType = TYPES[Number(e.detail.value)].value }

// 主单位: 走 /base/unit/app-list (登录即可) — base:unit:list 在 App 端没有任何授权行
const units = ref([])
const unitsFailed = ref(false)
const unitIndex = ref(-1)
const unitNames = computed(() => units.value.map(u => u.unitName || u.unitCode || ('单位#' + u.id)))
const pickedUnit = computed(() => unitIndex.value >= 0 ? units.value[unitIndex.value] : null)
function onPickUnit(e) { unitIndex.value = Number(e.detail.value) }

async function loadUnits() {
  unitsFailed.value = false
  if (units.value.length) return
  loading.value = true
  try {
    const r = await api.unitAppList()
    // R23: 拦截器已 resolve(d.data)，R<List> 直接就是数组，别再读 r.data
    const list = Array.isArray(r) ? r : ((r && r.data) || [])
    units.value = list.filter(u => u && u.unitName && String(u.unitName).trim())
    const preset = units.value.findIndex(u => u.unitName === '只')
    if (preset >= 0) unitIndex.value = preset
  } catch (e) {
    console.warn('[product-add.loadUnits] 失败:', e)
    unitsFailed.value = true
    if (e && e.code === 401) return
  } finally {
    loading.value = false
  }
}

function onToggleFood(e) { form.isFoodGrade = e.detail.value ? 1 : 0 }

function onScanBarcode() {
  doScan({
    onResult: (code) => {
      const v = String(code || '').trim()
      if (v) form.barcode = v.slice(0, 64)
    },
    onCancel: () => {}
  })
}

function toast(msg) {
  uni.showToast({ title: msg, icon: 'none' })
}

function goBack() {
  const pages = getCurrentPages()
  if (pages.length > 1) uni.navigateBack()
  else uni.reLaunch({ url: '/pages/dashboard/index' })
}

onLoad(() => {
  if (!guardPage('base')) return
  canAdd.value = (isAdmin() || getAppPermissions().includes(PERM)) && isPermEnabled('base', PERM)
  if (canAdd.value) loadUnits()
})

// 空串 → null；非法数字 → 返回 undefined 让 validate 拦下
function toNum(v) {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

function validate() {
  const code = String(form.productCode || '').trim()
  const name = String(form.productName || '').trim()
  if (!code) return '请输入商品编码'
  if (code.length > 50) return '商品编码不能超过 50 字'
  if (!name) return '请输入商品名称'
  if (name.length > 200) return '商品名称不能超过 200 字'
  if (!form.productType) return '请选择商品类型'
  if (!pickedUnit.value) return '请选择主单位'

  const nums = [
    ['销售价格', toNum(form.salesPrice)],
    ['采购价格', toNum(form.purchasePrice)],
    ['成本价格', toNum(form.costPrice)],
    ['安全库存', toNum(form.safetyStock)],
    ['长度', toNum(form.thickness)],
    ['宽度', toNum(form.width)],
    ['厚度', toNum(form.density)],
    ['克重', toNum(form.gramWeight)]
  ]
  for (const [label, v] of nums) {
    if (v === undefined) return label + ' 必须是数字'
    if (v !== null && v < 0) return label + ' 不能为负数'
    if (v !== null && v > 99999999) return label + ' 超出可输入范围'
  }
  const tax = toNum(form.taxRate)
  if (tax === undefined) return '税率必须是数字'
  if (tax !== null && (tax < 0 || tax > 100)) return '税率需在 0 ~ 100 之间'
  const limit = toNum(form.foodMigrationLimit)
  if (limit === undefined) return '总迁移量限值必须是数字'
  if (limit !== null && limit < 0) return '总迁移量限值不能为负数'
  const shelf = toNum(form.shelfLifeDays)
  if (shelf === undefined) return '保质期必须是数字'
  if (shelf !== null && (shelf < 0 || !Number.isInteger(shelf))) return '保质期需是不大于 0 的整数天'
  return ''
}

function buildPayload() {
  const u = pickedUnit.value
  const sales = toNum(form.salesPrice)
  const purchase = toNum(form.purchasePrice)
  const cost = toNum(form.costPrice)
  const product = {
    productCode: String(form.productCode).trim(),
    productName: String(form.productName).trim(),
    productType: form.productType,
    spec: String(form.spec || '').trim(),
    model: String(form.model || '').trim(),
    material: String(form.material || '').trim(),
    colorNo: String(form.colorNo || '').trim(),
    barcode: String(form.barcode || '').trim(),
    thickness: toNum(form.thickness),
    width: toNum(form.width),
    density: toNum(form.density),
    gramWeight: toNum(form.gramWeight),
    salesPrice: sales,
    purchasePrice: purchase,
    costPrice: cost,
    taxRate: toNum(form.taxRate),
    safetyStock: toNum(form.safetyStock),
    isFoodGrade: form.isFoodGrade,
    foodUseDesc: String(form.foodUseDesc || '').trim(),
    foodExecStd: String(form.foodExecStd || '').trim(),
    foodFilingNo: String(form.foodFilingNo || '').trim(),
    foodMigrationLimit: toNum(form.foodMigrationLimit),
    shelfLifeDays: toNum(form.shelfLifeDays),
    remark: String(form.remark || '').trim(),
    status: 1
  }
  // 主单位行: 后端 syncPriceFromMainUnit 会把这行的价格与 unitId 回填到商品，
  // 少了它 PC/App 后续都会报「该商品未配置单位」
  const unitsPayload = [{
    unitId: u.id,
    unitName: u.unitName,
    isMain: 1,
    conversionRate: 1,
    salesPrice: sales,
    purchasePrice: purchase,
    costPrice: cost,
    sortNo: 1
  }]
  return { product, units: unitsPayload }
}

async function onSubmit() {
  const err = validate()
  if (err) { toast(err); return }

  submitting.value = true
  try {
    await api.productAdd(buildPayload())
    toast('新增成功')
    goBack()
  } catch (e) {
    // 拦截器 (api/index.js:134) 已把后端 msg 弹成 toast，这里不重复弹；
    // 不 goBack ⇒ 停在页面上让用户改完再提交（条码/编码重复最需要看清那句原文）
    console.warn('[product-add.onSubmit] 失败:', e)
    if (e && e.code === 401) return
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 30px; background: #f5f5f5; min-height: 100vh; }
.card { background: #fff; border-radius: 8px; padding: 12px; margin-bottom: 12px; }
.section-title { font-size: 14px; font-weight: bold; display: block; margin-bottom: 8px; color: #333; border-left: 3px solid #1e6091; padding-left: 8px; }
.form-item { margin: 10px 0; }
.label { display: block; font-size: 12px; color: #999; margin-bottom: 4px; }
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; background: #fff; }
.picker-val { line-height: 36px; color: #333; }
.picker-val.placeholder { color: #c0c4cc; }
.textarea { width: 100%; height: 80px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 8px 10px; box-sizing: border-box; font-size: 14px; }
.hint { display: block; font-size: 11px; color: #999; margin-top: 4px; line-height: 1.5; }
.hint.warn { color: #e6a23c; }
.row-inline { display: flex; align-items: center; gap: 8px; }
.flex1 { flex: 1; }
.btn-scan { width: 44px; height: 36px; line-height: 36px; background: #f4f4f5; color: #606266; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 15px; padding: 0; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; font-size: 15px; }
.btn-block { width: 100%; }
.btn-plain { background: #f4f4f5; color: #606266; border: none; border-radius: 6px; padding: 8px 16px; font-size: 13px; }
.title { font-size: 15px; font-weight: bold; display: block; margin-bottom: 4px; }
.muted { color: #999; font-size: 11px; display: block; line-height: 1.6; }

/* Loading overlay */
.loading-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; z-index: 9999; display: flex; align-items: center; justify-content: center; }
.loading-mask { background: rgba(0,0,0,0.4); border-radius: 8px; padding: 20px 30px; }
.loading-text { color: #fff; font-size: 14px; }
</style>
