<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键字"><el-input v-model="query.keyword" placeholder="编码/名称/条码/规格" clearable /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="left">
          <el-button type="primary" @click="onAdd"><el-icon><Plus /></el-icon>新增商品</el-button>
          <el-button @click="loadData"><el-icon><Refresh /></el-icon>刷新</el-button>
        </div>
      </div>
      <el-table :data="data.records" border stripe size="default" v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column label="图片" width="60">
          <template #default="{ row }">
            <img v-if="row.imageUrl" :src="fullUrl(row.imageUrl)" class="table-thumb" @click="previewImg(row.imageUrl)" />
            <span v-else class="no-img">无图</span>
          </template>
        </el-table-column>
        <el-table-column prop="productCode" label="商品编码" width="140" />
        <el-table-column prop="productName" label="商品名称" />
        <el-table-column prop="spec" label="规格" width="160" />
        <el-table-column prop="model" label="型号" width="120" />
        <el-table-column prop="material" label="材质" width="80" />
        <el-table-column prop="thickness" label="长度" width="80" />
        <el-table-column prop="width" label="宽度" width="80" />
        <el-table-column prop="density" label="厚度" width="80" />
        <el-table-column prop="colorNo" label="色号" width="80" />
        <el-table-column prop="salesPrice" label="价格" width="100" align="right" />
        <el-table-column prop="safetyStock" label="安全库存" width="100" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status===1?'success':'info'">{{ row.status===1?'启用':'停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="onStock(row)">库存</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        :page-sizes="[10,20,50,100]" @current-change="loadData" @size-change="loadData" />
    </div>

    <!-- 新增/编辑 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑商品' : '新增商品'" width="900px">
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="商品编码" prop="productCode"><el-input v-model="form.productCode" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="商品名称" prop="productName"><el-input v-model="form.productName" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="型号"><el-input v-model="form.model" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="规格"><el-input v-model="form.spec" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="材质"><el-input v-model="form.material" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="色号"><el-input v-model="form.colorNo" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6"><el-form-item label="长度"><el-input v-model.number="form.thickness" type="number" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="宽度"><el-input v-model.number="form.width" type="number" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="厚度"><el-input v-model.number="form.density" type="number" /></el-form-item></el-col>
          <el-col :span="6"><el-form-item label="克重(g/个)"><el-input v-model.number="form.gramWeight" type="number" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6"><el-form-item label="条形码"><el-input v-model="form.barcode" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="价格"><el-input v-model.number="form.salesPrice" type="number" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="成本价"><el-input v-model.number="form.costPrice" type="number" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="税率(%)"><el-input v-model.number="form.taxRate" type="number" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="安全库存"><el-input v-model.number="form.safetyStock" type="number" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="批次管理"><el-switch v-model="form.isBatch" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="序列号"><el-switch v-model="form.isSn" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
        </el-row>

        <!-- 商品照片 -->
        <el-row :gutter="12">
          <el-col :span="24">
            <el-form-item label="商品照片">
              <div class="image-uploader">
                <input ref="fileInput" type="file" accept="image/*" multiple style="display:none" @change="onFileChange" />
                <div class="image-list" v-if="form.images && form.images.length">
                  <div v-for="(img, idx) in form.images" :key="idx" class="image-item">
                    <img :src="fullUrl(img)" @click="previewImg(img)" />
                    <el-button class="del-btn" type="danger" :icon="Delete" circle size="small" @click="removeImage(idx)" />
                  </div>
                </div>
                <el-button @click="triggerFile" type="primary" plain :loading="uploading">
                  <el-icon><Plus /></el-icon>上传图片
                </el-button>
                <div class="tip">支持 JPG/PNG/GIF, 单张最大 5MB</div>
              </div>
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 备注 -->
        <el-row :gutter="12">
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="商品备注信息 (最多 500 字)" />
            </el-form-item>
          </el-col>
        </el-row>

        <div class="units-section">
          <el-button @click="addUnit" type="primary" plain size="small"><el-icon><Plus /></el-icon>添加单位</el-button>
          <div v-for="(unit, idx) in form.units" :key="idx" class="unit-row">
            <el-tag :type="unit.isMain === 1 ? 'success' : 'info'" @click="setMainUnit(idx)" style="cursor:pointer; margin-right:8px">主</el-tag>
            <el-input v-model="unit.unitName" placeholder="单位名称" size="small" style="width:100px;margin-right:8px" />
            <el-input v-model.number="unit.conversionRate" type="number" size="small" style="width:120px;margin-right:8px" placeholder="换算率" />
            <el-input v-model.number="unit.salesPrice" type="number" size="small" style="width:100px;margin-right:8px" placeholder="价格" />
            <el-input v-model.number="unit.costPrice" type="number" size="small" style="width:100px;margin-right:8px" placeholder="成本价" />
            <el-button link type="danger" @click="form.units.splice(idx, 1)">删除</el-button>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- 图片预览 -->
    <el-dialog v-model="previewVisible" width="600px" :show-close="true">
      <img v-if="previewImgUrl" :src="previewImgUrl" style="width:100%" />
    </el-dialog>

    <!-- 库存 -->
    <el-dialog v-model="stockVisible" :title="`库存: ${stockDetail?.product?.productName}`" width="700px">
      <el-table :data="stockDetail?.stockSummary || []" size="small" border>
        <el-table-column prop="warehouseId" label="仓库ID" />
        <el-table-column prop="warehouseName" label="仓库" />
        <el-table-column prop="qty" label="库存数量" align="right" />
        <el-table-column prop="availableQty" label="可用数量" align="right" />
        <el-table-column prop="totalCost" label="总成本" align="right" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { productApi } from '@/api/base'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import request from '@/utils/request'

const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const stockVisible = ref(false)
const stockDetail = ref(null)
const submitting = ref(false)
const formRef = ref()
const fileInput = ref()
const uploading = ref(false)
const previewVisible = ref(false)
const previewImgUrl = ref('')

const form = reactive({
  id: null, productCode: '', productName: '', spec: '', model: '', material: '',
  thickness: null, width: null, density: null, gramWeight: null, colorNo: '', barcode: '',
  salesPrice: 0, purchasePrice: 0, costPrice: 0,
  taxRate: 13.00, safetyStock: 0, isBatch: 1, isSn: 0, status: 1,
  remark: '', images: [], imageUrl: '', units: []
})
const rules = {
  productCode: [{ required: true, message: '请输入编码' }],
  productName: [{ required: true, message: '请输入名称' }]
}

// 解析 baseURL 转完整 URL
function fullUrl(url) {
  if (!url) return ''
  if (url.startsWith('http')) return url
  return (window.location.origin || '') + url
}

function triggerFile() {
  fileInput.value && fileInput.value.click()
}

async function onFileChange(e) {
  const files = e.target.files
  if (!files || !files.length) return
  uploading.value = true
  try {
    for (const file of files) {
      if (file.size > 5 * 1024 * 1024) {
        ElMessage.warning(`${file.name} 超过 5MB, 已跳过`)
        continue
      }
      const fd = new FormData()
      fd.append('file', file)
      const r = await request({
        url: '/system/upload/file',
        method: 'POST',
        data: fd,
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      if (r.code === 200) {
        form.images.push(r.data.url)
      } else {
        ElMessage.error('上传失败: ' + (r.msg || '未知错误'))
      }
    }
  } finally {
    uploading.value = false
    // 清空 input 允许重复上传同名文件
    e.target.value = ''
  }
}

function removeImage(idx) {
  form.images.splice(idx, 1)
}

function previewImg(url) {
  previewImgUrl.value = fullUrl(url)
  previewVisible.value = true
}

// 把 images 数组转成逗号分隔字符串给后端
function normalizeImages() {
  const urls = form.images || []
  form.imageUrl = urls[0] || ''
}

async function loadData() {
  loading.value = true
  try {
    const r = await productApi.page(query)
    data.value = r.data
  } finally { loading.value = false }
}

function reset() { query.keyword = ''; loadData() }

function onAdd() {
  form.id = null
  form.productCode = ''
  form.productName = ''
  form.spec = ''
  form.model = ''
  form.material = ''
  form.thickness = null
  form.width = null
  form.density = null
  form.gramWeight = null
  form.colorNo = ''
  form.barcode = ''
  form.salesPrice = 0
  form.purchasePrice = 0
  form.costPrice = 0
  form.taxRate = 13.00
  form.safetyStock = 0
  form.isBatch = 1
  form.isSn = 0
  form.status = 1
  form.remark = ''
  form.images = []
  form.imageUrl = ''
  form.units = [{ unitName: '卷', isMain: 1, conversionRate: 1 }]
  dialogVisible.value = true
}

async function onEdit(row) {
  const r = await productApi.detail(row.id)
  const d = r.data
  Object.assign(form, d.product)
  form.units = d.units || []
  // 解析 imageUrl 到 images 数组 (支持逗号分隔多图)
  const imgStr = form.imageUrl || ''
  form.images = imgStr ? imgStr.split(',').filter(s => s.trim()) : []
  dialogVisible.value = true
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除 ${row.productName} ?`, '提示', { type: 'warning' })
  await productApi.delete(row.id)
  ElMessage.success('删除成功')
  loadData()
}

function addUnit() {
  form.units.push({ unitName: '', isMain: 0, conversionRate: 1, salesPrice: 0, costPrice: 0 })
}

function setMainUnit(index) {
  form.units.forEach((u, i) => { u.isMain = i === index ? 1 : 0 })
}

async function onSubmit() {
  await formRef.value.validate()
  submitting.value = true
  try {
    // 将 images 数组保存到 imageUrl (逗号分隔)
    normalizeImages()
    const product = { ...form }
    delete product.images  // 不传给后端
    if (form.id) await productApi.update({ product, units: form.units })
    else await productApi.add({ product, units: form.units })
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadData()
  } finally { submitting.value = false }
}

async function onStock(row) {
  const r = await productApi.detail(row.id)
  stockDetail.value = r.data
  stockVisible.value = true
}

onMounted(loadData)
</script>

<style scoped>
.pager { margin-top: 12px; text-align: right; }
.units-section { margin-bottom: 18px; }
.unit-row { display: flex; align-items: center; margin-top: 8px; gap: 4px; }
.table-thumb { width: 36px; height: 36px; object-fit: cover; border-radius: 4px; cursor: pointer; }
.no-img { color: #ccc; font-size: 12px; }
.image-uploader { display: flex; flex-direction: column; gap: 8px; }
.image-list { display: flex; flex-wrap: wrap; gap: 8px; }
.image-item { position: relative; width: 100px; height: 100px; }
.image-item img { width: 100%; height: 100%; object-fit: cover; border-radius: 6px; border: 1px solid #eee; cursor: pointer; }
.del-btn { position: absolute; top: -6px; right: -6px; }
.tip { color: #999; font-size: 12px; }
</style>
