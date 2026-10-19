<template>
  <div class="page">
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">供应链 / 采购建议</div>
        <h1 class="xk-title">采购建议 (基于库存预警)</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="loadList"><el-icon><Search /></el-icon>查询</el-button>
        <el-button type="primary" :disabled="!selected.length" @click="onGenOrder"><el-icon><ShoppingCart /></el-icon>生成采购单草稿</el-button>
      </div>
    </div>

    <div class="tip">
      依据「安全库存 − 当前库存」自动计算建议采购量。勾选需要补货的原料 → 选供应商 → 一键生成采购订单草稿，到「采购订单」列表审核后采购入库。
    </div>

    <el-table :data="rows" border stripe v-loading="loading" empty-text="暂无库存预警, 无需补货">
      <el-table-column type="selection" width="46" />
      <el-table-column prop="productCode" label="编码" width="150" />
      <el-table-column prop="productName" label="商品" min-width="160" />
      <el-table-column prop="spec" label="规格" width="140" />
      <el-table-column prop="warehouseName" label="仓库" width="110">
        <template #default="{ row }">{{ row.warehouseName || '-' }}</template>
      </el-table-column>
      <el-table-column prop="qty" label="当前库存" width="100" align="right">
        <template #default="{ row }"><b class="danger">{{ row.qty }}</b></template>
      </el-table-column>
      <el-table-column prop="safetyStock" label="安全库存" width="100" align="right" />
      <el-table-column label="缺口" width="100" align="right">
        <template #default="{ row }"><b class="danger">{{ row.shortage }}</b></template>
      </el-table-column>
      <el-table-column label="建议采购量" width="110" align="right">
        <template #default="{ row }">{{ row.shortage }}</template>
      </el-table-column>
    </el-table>

    <!-- 一键生成采购单弹窗 -->
    <el-dialog v-model="genVisible" title="生成采购订单草稿" width="560px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="供应商" required>
          <el-select v-model="genForm.supplierId" placeholder="请选择供应商" filterable style="width:100%">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入库备注">
          <el-input v-model="genForm.remark" placeholder="可选, 如: 库存补货" />
        </el-form-item>
        <el-form-item>
          <el-alert type="info" :closable="false" show-icon
            :title="'将生成 1 张草稿采购单, 共 ' + selected.length + ' 行, 建议量合计 ' + totalQty + ' (单价取该供应商历史成交价)'"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="genVisible = false">取消</el-button>
        <el-button type="primary" :loading="genLoading" @click="confirmGen">生成草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, ShoppingCart } from '@element-plus/icons-vue'
import { stockApi } from '@/api/inventory'
import { supplierApi } from '@/api/base'
import { purOrderApi } from '@/api/purchase'

const router = useRouter()
const loading = ref(false)
const rows = ref([])
const selected = ref([])
const suppliers = ref([])
const genVisible = ref(false)
const genLoading = ref(false)
const genForm = ref({ supplierId: null, remark: '' })

// 库存预警行 → 采购建议行 (shortage 字段已由后端 warningList 提供 = safety_stock - qty, 非负)
async function loadList() {
  loading.value = true
  try {
    const r = await stockApi.warningList()
    rows.value = (r.data || [])
      .map(it => ({
        productId: it.product_id,
        productCode: it.product_code || '',
        productName: it.product_name || '-',
        spec: it.spec || '',
        warehouseName: it.wh_name || it.warehouseName || '',
        qty: Number(it.qty || 0),
        safetyStock: it.safety_stock != null ? Number(it.safety_stock) : (it.p_safety_stock != null ? Number(it.p_safety_stock) : 0),
        shortage: it.shortage != null ? Number(it.shortage) : 0
      }))
      .filter(it => it.shortage > 0)
  } catch (e) {
    if (import.meta.env.DEV) console.warn('[PurchaseSuggest] 加载失败', e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

const totalQty = computed(() => selected.value.reduce((s, r) => s + (r.shortage || 0), 0))

async function onGenOrder() {
  if (!selected.value.length) { ElMessage.warning('请先勾选需要补货的原料'); return }
  genForm.value = { supplierId: null, remark: '库存补货' }
  try {
    const s = await supplierApi.list()
    suppliers.value = (s.data || []).filter(x => x.status === 1 || x.status == null)
  } catch (e) { suppliers.value = [] }
  genVisible.value = true
}

async function confirmGen() {
  if (!genForm.value.supplierId) { ElMessage.warning('请选择供应商'); return }
  const sup = suppliers.value.find(x => x.id === genForm.value.supplierId)
  genLoading.value = true
  try {
    // 逐行取该供应商历史成交价, 无则 0 (草稿后可在采购订单页补价)
    const details = []
    for (const r of selected.value) {
      let price = 0
      try {
        const p = await purOrderApi.getLastPrice(genForm.value.supplierId, r.productId)
        price = Number(p?.data || 0)
      } catch (e) { price = 0 }
      details.push({
        productId: r.productId,
        productCode: r.productCode || null,
        productName: r.productName || null,
        spec: r.spec || null,
        qty: r.shortage,
        price: price
      })
    }
    await purOrderApi.add({
      supplierId: genForm.value.supplierId,
      supplierName: sup?.supplierName || null,
      remark: genForm.value.remark || '库存补货',
      billStatus: 'DRAFT',
      details
    })
    ElMessage.success(`已生成采购订单草稿 (${selected.value.length} 行, 建议量合计 ${totalQty.value}), 可到「采购订单」审核`)
    genVisible.value = false
    router.push('/purchase/order')
  } catch (e) {
    ElMessage.error(e.message || '生成失败')
  } finally {
    genLoading.value = false
  }
}

onMounted(loadList)
</script>

<style scoped lang="scss">
.page { display: flex; flex-direction: column; gap: 14px; }
.tip { color: var(--muted, #6f7d90); font-size: 12px; background: #f7f9fc; border: 1px solid #e8edf3; border-radius: 8px; padding: 10px 12px; }
.danger { color: var(--danger, #f56c6c); }
</style>
