<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="单号"><el-input v-model="query.billNo" placeholder="单号" clearable /></el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="onAdd">新增</el-button>
      </div>
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="billNo" label="单号" width="180" />
        <el-table-column prop="billDate" label="日期" width="120" />
        <el-table-column prop="customerName" label="客户" />
        <el-table-column prop="totalQty" label="数量" width="100" align="right" />
        <!-- v1.1.41: 已发货 / 未发货数量 -->
        <el-table-column label="已发/未发" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.billStatus==='CHECKED'" style="font-size:12px;color:#67c23a">{{ row.shippedQty || 0 }} / {{ (row.totalQty || 0) - (row.shippedQty || 0) }}</span>
            <span v-else style="font-size:12px;color:#909399">—</span>
          </template>
        </el-table-column>
        <!-- v1.1.19+: 含税单价口径, totalAmount = totalAmountTax = 开单金额, 只显示「金额」一列 -->
        <el-table-column prop="totalAmount" label="金额" width="120" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus==='DRAFT'?'info':'success'">{{ row.billStatus === 'DRAFT' ? '草稿' : '已审核' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="460" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('sales:order:edit')" link type="primary" size="small" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('sales:order:delete')" link type="danger" size="small" @click="onDelete(row)">删除</el-button>
            <el-button v-if="row.billStatus==='DRAFT' && userStore.hasPerm('sales:order:check')" link type="success" size="small" @click="onCheck(row)">审核</el-button>
            <el-button v-if="row.billStatus==='CHECKED' && userStore.hasPerm('sales:order:uncheck')" link type="warning" size="small" @click="onUncheck(row)">反审核</el-button>
            <!-- v1.1.35: 已审核订单一键生成销售出库 (走现有 salDeliveryApi.add) -->
            <el-button v-if="row.billStatus==='CHECKED' && userStore.hasPerm('sales:delivery:add')" link type="primary" size="small" @click="onGenerateDelivery(row)">生成出库单</el-button>
            <!-- v1.1.38: 查看该订单已关联的出库单 (追溯) -->
            <el-button v-if="userStore.hasPerm('sales:delivery:list')" link type="info" size="small" @click="onViewLinkedDeliveries(row)">关联出库单</el-button>
            <!-- v1.1.41: 查看该订单发货详情 (已发/未发数量) -->
            <el-button v-if="row.billStatus==='CHECKED' && userStore.hasPerm('sales:order:list')" link type="success" size="small" @click="onViewDeliverySummary(row)">发货详情</el-button>
            <!-- v1.1.36: 打印 (浏览器 + 飞鹅云, 与 Delivery.vue 同模式) -->
            <el-dropdown v-if="['DRAFT','CHECKED'].includes(row.billStatus)" trigger="click" @command="(cmd) => onPrintCommand(cmd, row)">
              <el-button link type="warning" size="small">
                打印<el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="browser">浏览器打印</el-dropdown-item>
                  <el-dropdown-item command="feie-preview">飞鹅打印预览</el-dropdown-item>
                  <el-dropdown-item command="feie-print" divided>飞鹅云打印</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        @current-change="loadData" @size-change="loadData" />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑订单' : '新增订单'" width="900px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="客户"><el-select v-model="form.customerId" placeholder="请选择客户" filterable style="width:100%">
            <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
          </el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="仓库"><el-select v-model="form.warehouseId" placeholder="请选择仓库" style="width:100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
          </el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="交货日期"><el-date-picker v-model="form.deliveryDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="付款方式"><el-select v-model="form.payType" style="width:100%">
            <el-option label="款到发货" value="PREPAY" />
            <el-option label="月结" value="MONTHLY" />
            <el-option label="货到付款" value="ARRIVAL" />
          </el-select></el-form-item></el-col>
          <!-- v1.1.37: 采购订单号 (客户 PO 号) -->
          <el-col :span="8"><el-form-item label="采购订单号"><el-input v-model="form.poNo" placeholder="客户PO号" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="交货方式"><el-select v-model="form.deliveryMethod" style="width:100%">
            <el-option label="送货" value="DELIVERY" />
            <el-option label="自提" value="PICKUP" />
            <el-option label="专车直送" value="DIRECT" />
          </el-select></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="16"><el-form-item label="备注"><el-input v-model="form.remark" /></el-form-item></el-col>
          <!-- 留空占位保持列对齐 (无新字段时可删) -->
          <el-col :span="8"></el-col>
        </el-row>
        <!-- 商品明细 -->
        <el-form-item label="明细">
          <el-button size="small" type="primary" plain @click="addDetail">添加商品</el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px">
            <el-table-column label="商品" width="200">
              <template #default="{ row }">
                <el-select v-model="row.productId" placeholder="商品" filterable @change="onProductChange(row)">
                  <el-option v-for="p in products" :key="p.id" :label="p.productName" :value="p.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column prop="productCode" label="编码" width="120" />
            <el-table-column prop="productName" label="名称" />
            <el-table-column label="数量" width="120">
              <template #default="{ row }"><el-input-number v-model="row.qty" :min="0" :step-strictly="false" size="small" /></template>
            </el-table-column>
            <el-table-column label="单价(含税)" width="120">
              <template #default="{ row }"><el-input-number v-model="row.price" :min="0" :precision="4" :step-strictly="false" size="small" /></template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ row, $index }"><el-button link type="danger" @click="form.details.splice($index, 1)">删除</el-button></template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- v1.1.35: 由销售订单一键生成销售出库单 (轻量级弹窗, 自动带入订单客户/仓库/明细) -->
    <el-dialog v-model="generateDialogVisible"
      :title="sourceOrder ? '生成出库单 (源 ' + sourceOrder.billNo + ')' : '生成出库单'"
      width="1000px" destroy-on-close>
      <el-form :model="deliveryForm" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="单据日期">
            <el-date-picker v-model="deliveryForm.billDate" type="date"
              value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="客户">
            <el-input v-model="deliveryForm.customerName" disabled />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="仓库">
            <el-select v-model="deliveryForm.warehouseId" filterable style="width:100%">
              <el-option v-for="w in warehouses" :key="w.id"
                :label="w.warehouseName" :value="w.id" />
            </el-select>
          </el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="收货地址">
            <el-input v-model="deliveryForm.address" />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="收货电话">
            <el-input v-model="deliveryForm.phone" />
          </el-form-item></el-col>
          <el-col :span="8"><el-form-item label="整单折扣">
            <el-input-number v-model="deliveryForm.discountAmount" :min="0" :step-strictly="false" />
          </el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <!-- v1.1.40: 交货方式 (下拉可改, 默认来自源订单) -->
          <el-col :span="8"><el-form-item label="交货方式">
            <el-select v-model="deliveryForm.deliveryMethod" style="width:100%">
              <el-option label="送货" value="DELIVERY" />
              <el-option label="自提" value="PICKUP" />
              <el-option label="专车直送" value="DIRECT" />
            </el-select>
            <span style="color:#999;font-size:12px">源订单交货方式, 可修改</span>
          </el-form-item></el-col>
          <!-- v1.1.41: 采购订单号 (只读, 来自源订单) -->
          <el-col :span="8"><el-form-item label="采购订单号">
            <el-input :value="deliveryForm.poNo || '—'" disabled />
            <span style="color:#999;font-size:12px">源订单采购订单号, 自动写入明细</span>
          </el-form-item></el-col>
          <el-col :span="8"></el-col>
        </el-row>

        <el-form-item label="商品明细">
          <el-table :data="deliveryForm.details" size="small" border max-height="380">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="productCode" label="编码" width="120" />
            <el-table-column prop="productName" label="名称" />
            <el-table-column prop="spec" label="规格" width="120" />
            <el-table-column prop="unitName" label="单位" width="60" />
            <el-table-column label="数量" width="120">
              <template #default="{ row }">
                <el-input v-model="row.qty" size="small" type="text" inputmode="decimal"
                  @blur="row.qty = normNum(row.qty)" placeholder="0" />
              </template>
            </el-table-column>
            <el-table-column label="单价(含税)" width="120">
              <template #default="{ row }">
                <el-input v-model="row.price" size="small" type="text" inputmode="decimal"
                  @blur="row.price = normNum(row.price)" placeholder="0" />
              </template>
            </el-table-column>
            <el-table-column label="金额" width="100" align="right">
              <template #default="{ row }">
                <span>{{ ((+row.qty || 0) * (+row.price || 0)).toString() }}</span>
              </template>
            </el-table-column>
            <el-table-column label="批次" width="160">
              <template #default="{ row }">
                <el-input v-model="row.batchNo" size="small" placeholder="可选" />
              </template>
            </el-table-column>
            <el-table-column label="库位" width="100">
              <template #default="{ row }">
                <el-input v-model="row.locationName" size="small" />
              </template>
            </el-table-column>
            <!-- v1.1.47: 采购订单号 (客户 PO 号, 从源订单明细自动带入, 用户可改) -->
            <el-table-column label="采购订单号" width="140">
              <template #default="{ row }"><el-input v-model="row.poNo" size="small" placeholder="可选" /></template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ $index }">
                <el-button link type="danger" size="small" @click="removeDeliveryDetail($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <p style="color:#999;font-size:12px;margin-top:6px">
            提示: 数量默认为订单全量, 如部分发货请手工调整. 源订单 {{ sourceOrder?.billNo || '-' }} 关联字段已自动写入.
          </p>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="deliveryForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="generateDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onConfirmGenerate" :loading="submitting">保存为草稿</el-button>
      </template>
    </el-dialog>

    <!-- v1.1.38: 查看该订单已关联的出库单 (追溯入口) -->
    <el-dialog v-model="linkedDeliveryDialogVisible"
      :title="linkedSourceOrder ? '关联出库单 (源 ' + linkedSourceOrder.billNo + ')' : '关联出库单'"
      width="960px" destroy-on-close>
      <div v-loading="linkedDeliveryLoading" style="min-height:120px;">
        <el-table :data="linkedDeliveries" size="small" border stripe v-if="!linkedDeliveryLoading">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="billNo" label="出库单号" width="180">
            <template #default="{ row }">
              <el-button link type="primary" size="small"
                @click="jumpToDelivery(row.id)">{{ row.billNo }}</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="billDate" label="日期" width="120" />
          <el-table-column prop="customerName" label="客户" width="140" />
          <el-table-column prop="warehouseName" label="仓库" width="100" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.billStatus==='DRAFT'?'info':'success'" size="small">
                {{ row.billStatus==='DRAFT'?'草稿':'已审核' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="totalQty" label="数量" width="80" align="right" />
          <el-table-column prop="totalAmount" label="金额" width="110" align="right">
            <template #default="{ row }">
              <span>¥{{ (row.totalAmount || 0).toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="primary" size="small"
                @click="jumpToDelivery(row.id)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!linkedDeliveryLoading && linkedDeliveries.length === 0"
          description="该订单暂无关联出库单" :image-size="80" />
      </div>
      <p style="color:#999;font-size:12px;margin-top:8px">
        提示: 点击单号或「查看」跳转到销售出库详情页. 源订单 {{ linkedSourceOrder?.billNo || '-' }}.
      </p>
      <template #footer>
        <el-button @click="linkedDeliveryDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="reloadLinkedDeliveries" :loading="linkedDeliveryLoading">刷新</el-button>
      </template>
    </el-dialog>

    <!-- v1.1.41: 发货详情弹窗 (已发/未发数量) -->
    <el-dialog v-model="deliverySummaryDialogVisible"
      :title="deliverySummaryOrder ? '发货详情 (源 ' + deliverySummaryOrder.billNo + ')' : '发货详情'"
      width="800px" destroy-on-close>
      <div v-loading="deliverySummaryLoading" style="min-height:120px;">
        <el-table :data="deliverySummaryData" size="small" border stripe v-if="!deliverySummaryLoading">
          <el-table-column type="index" label="#" width="50" />
          <el-table-column prop="productCode" label="编码" width="120" />
          <el-table-column prop="productName" label="名称" />
          <el-table-column prop="spec" label="规格" width="120" />
          <el-table-column prop="unitName" label="单位" width="60" />
          <el-table-column label="订单数量" width="100" align="right">
            <template #default="{ row }">{{ row.qty != null ? Number(row.qty).toFixed(2).replace(/\.?0+$/, '') : '0' }}</template>
          </el-table-column>
          <el-table-column label="已发货" width="100" align="right">
            <template #default="{ row }"><span style="color:#67c23a">{{ row.shippedQty != null ? Number(row.shippedQty).toFixed(2).replace(/\.?0+$/, '') : '0' }}</span></template>
          </el-table-column>
          <el-table-column label="未发货" width="100" align="right">
            <template #default="{ row }">
              <span :style="{ color: Number(row.unshippedQty) > 0 ? '#e6a23c' : '#909399' }">
                {{ row.unshippedQty != null ? Number(row.unshippedQty).toFixed(2).replace(/\.?0+$/, '') : '0' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag v-if="Number(row.unshippedQty) <= 0" type="success" size="small">已发完</el-tag>
              <el-tag v-else type="warning" size="small">部分发货</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!deliverySummaryLoading && deliverySummaryData.length === 0"
          description="该订单暂无明细" :image-size="80" />
      </div>
      <p style="color:#999;font-size:12px;margin-top:8px">
        提示: 已发货数量来源于已审核出库单的明细 qty 累计. 源订单 {{ deliverySummaryOrder?.billNo || '-' }}.
      </p>
      <template #footer>
        <el-button @click="deliverySummaryDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="reloadDeliverySummary" :loading="deliverySummaryLoading">刷新</el-button>
      </template>
    </el-dialog>

    <!-- 飞鹅云打印预览弹窗 (v1.1.36) -->
    <el-dialog v-model="feiePreviewVisible" title="飞鹅云打印预览" width="560px" destroy-on-close>
      <div v-loading="feiePreviewLoading" style="min-height:200px;">
        <pre style="white-space:pre-wrap;font-family:SimSun,monospace;font-size:12px;background:#fafafa;padding:12px;border-radius:4px;max-height:500px;overflow:auto;">{{ feiePreviewHtml }}</pre>
      </div>
      <template #footer>
        <el-button @click="feiePreviewVisible=false">关闭</el-button>
        <el-button type="primary" :loading="feiePrinting" :disabled="!feiePreviewHtml" @click="feieConfirmPrint">确认打印</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { salOrderApi, salDeliveryApi } from '@/api/sales'
import { useUserStore } from '@/store/user'
import { customerApi, warehouseApi, productApi } from '@/api/base'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { usePrint, BIZ_TYPES } from '@/composables/usePrint'
import { feiePrintApi } from '@/api/feie'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

// v1.1.33: el-input + inputmode=decimal 数字归一化 (与 Delivery.vue 同模式)
const normNum = (v) => {
  if (v == null || v === '') return 0
  const n = Number(String(v).replace(/,/g, ''))
  return isFinite(n) ? n : 0
}

// v1.1.40: 交货方式枚举值 ↔ 中文标签双向映射 (与后端 SalOrderService.mapDeliveryMethod 一致)
const DELIVERY_METHOD_MAP = { 'DELIVERY': '送货', 'PICKUP': '自提', 'DIRECT': '专车直送' }
const mapDeliveryMethod   = (code) => DELIVERY_METHOD_MAP[code] || code
const mapCodeFromLabel    = (label) => Object.entries(DELIVERY_METHOD_MAP).find(([, v]) => v === label)?.[0] || label

const userStore = useUserStore()
const router = useRouter()

const query = reactive({ pageNum: 1, pageSize: 20, billNo: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = ref({ id: null, customerId: null, warehouseId: null, deliveryDate: null, payType: 'PREPAY', remark: '', details: [] })
const customers = ref([])
const warehouses = ref([])
const products = ref([])
const { taxSeparation, loadTaxSeparation } = useTaxSeparation()

// v1.1.35: 由销售订单生成出库单的轻量级弹窗状态
const generateDialogVisible = ref(false)
const sourceOrder = ref(null)        // 源订单完整 SalOrder
const generating = ref(false)        // 加载源订单中
const deliveryForm = reactive({
  billDate: '', customerId: null, customerName: '',
  warehouseId: null, address: '', phone: '',
  discountAmount: 0, tailAmount: 0, remark: '',
  details: [],
  // v1.1.41: 交货方式 / 采购订单号 (从源订单自动带入)
  deliveryMethod: '', poNo: '',
  // 联动字段 (后端 BaseMapper.insert 自动写入 sal_delivery.order_id/order_no)
  orderId: null, orderNo: ''
})

// v1.1.38: 关联出库单弹窗状态
const linkedDeliveryDialogVisible = ref(false)
const linkedSourceOrder = ref(null)        // 源订单 (显示标题)
const linkedDeliveryLoading = ref(false)
const linkedDeliveries = ref([])
const linkedOrderId = ref(null)            // 当前查询的订单 ID

// v1.1.41: 发货详情弹窗状态
const deliverySummaryDialogVisible = ref(false)
const deliverySummaryOrder = ref(null)
const deliverySummaryLoading = ref(false)
const deliverySummaryData = ref([])
const deliverySummaryOrderId = ref(null)

async function loadData() {
  loading.value = true
  try { data.value = (await salOrderApi.page(query)).data } finally { loading.value = false }
}

async function loadOptions() {
  if (customers.value.length === 0) customers.value = (await customerApi.list()).data || []
  if (warehouses.value.length === 0) warehouses.value = (await warehouseApi.list()).data || []
  if (products.value.length === 0) products.value = (await productApi.page({ pageNum: 1, pageSize: 200 })).data?.records || []
}

function onAdd() {
  loadTaxSeparation()
  form.value = { id: null, customerId: null, warehouseId: null, deliveryDate: null, payType: 'PREPAY', remark: '', details: [] }
  loadOptions()
  dialogVisible.value = true
}

async function onEdit(row) {
  loadTaxSeparation()
  const r = await salOrderApi.detail(row.id)
  form.value = { ...r.data, details: r.data.details || [] }
  loadOptions()
  dialogVisible.value = true
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除订单 ${row.billNo}?`, '提示', { type: 'warning' })
  try {
    await salOrderApi.delete(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

// v1.1.11+ 审核 (status-only, 下游出库单触发库存账)
async function onCheck(row) {
  try {
    await ElMessageBox.confirm(`确认审核订单 ${row.billNo}?`, '审核确认', { type: 'warning', confirmButtonText: '确认审核', cancelButtonText: '取消' })
  } catch { return }
  try {
    await salOrderApi.check(row.id); ElMessage.success('审核成功'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '审核失败')
  }
}

async function onUncheck(row) {
  try {
    await ElMessageBox.confirm(`确认反审核订单 ${row.billNo}? 单据状态将回到「草稿」.`, '反审核确认', { type: 'warning', confirmButtonText: '确认反审核', cancelButtonText: '取消' })
  } catch { return }
  try {
    await salOrderApi.uncheck(row.id); ElMessage.success('反审核成功'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '反审核失败')
  }
}

// v1.1.35: 已审核订单 → 加载订单明细 → 弹出生成出库单弹窗
async function onGenerateDelivery(row) {
  generating.value = true
  try {
    const r = await salOrderApi.detail(row.id)
    const o = r.data
    if (!o || !o.details || !o.details.length) {
      ElMessage.warning('订单无明细, 无法生成'); return
    }
    sourceOrder.value = o
    // 重置 deliveryForm (避免上次残留)
    deliveryForm.billDate = new Date().toISOString().substring(0, 10)
    deliveryForm.customerId = o.customerId
    deliveryForm.customerName = o.customerName
    deliveryForm.address = ''
    deliveryForm.phone = ''
    deliveryForm.discountAmount = 0
    deliveryForm.tailAmount = 0
    deliveryForm.remark = ''
    deliveryForm.orderId = o.id
    deliveryForm.orderNo = o.billNo
    // v1.1.40: 交货方式用中文 label (后端 detail() 注入 deliveryMethodLabel)
    const methodLabel = o.deliveryMethodLabel || mapDeliveryMethod(o.deliveryMethod || '')
    deliveryForm.deliveryMethod = methodLabel ? mapCodeFromLabel(methodLabel) : (o.deliveryMethod || '')
    deliveryForm.poNo = o.poNo || ''
    // 订单明细 → 出库明细 (默认值 = 全量; 用户可手工改数量做部分发货)
    deliveryForm.details = (o.details || []).map((d, idx) => ({
      productId: d.productId,
      productCode: d.productCode,
      productName: d.productName,
      spec: d.spec,
      unitId: d.unitId,
      unitName: d.unitName,
      qty: d.qty != null ? Number(d.qty) : 0,
      price: d.price != null ? Number(d.price) : 0,
      taxRate: d.taxRate != null ? Number(d.taxRate) : 13,
      lineNo: idx + 1,
      orderDetailId: d.id,                  // 联动字段, 后端自动写入 sal_delivery_detail
      poNo: d.poNo || '',                   // v1.1.47: 采购订单号从源订单明细自动带入
      batchNo: '',
      locationName: '',
      remark: ''
    }))
    // 加载仓库列表 (loadOptions 内部已做幂等缓存)
    await loadOptions()
    deliveryForm.warehouseId = o.warehouseId
    generateDialogVisible.value = true
  } catch (e) {
    ElMessage.error('加载订单失败: ' + (e.message || '未知错误'))
  } finally { generating.value = false }
}

// v1.1.35: 确认生成 → 走现有 salDeliveryApi.add (BaseMapper.insert 自动写 orderId/orderNo/orderDetailId)
async function onConfirmGenerate() {
  if (!deliveryForm.warehouseId) {
    ElMessage.warning('请选择仓库'); return
  }
  if (!deliveryForm.details.length) {
    ElMessage.warning('订单无明细'); return
  }
  submitting.value = true
  try {
    let totalAmount = 0
    deliveryForm.details.forEach(d => {
      totalAmount += (+d.qty || 0) * (+d.price || 0)
    })
    const discount = +deliveryForm.discountAmount || 0
    const tail = +deliveryForm.tailAmount || 0
    const payload = {
      ...deliveryForm,
      totalAmount: totalAmount - discount - tail,
      taxAmount: 0,
      totalAmountTax: totalAmount - discount - tail
    }
    // 剔除前端 UI 辅助字段, 避免脏数据传后端
    payload.details = deliveryForm.details.map(d => {
      const cleaned = { ...d }
      delete cleaned._units
      delete cleaned._priceFromUnit
      return cleaned
    })
    await salDeliveryApi.add(payload)
    ElMessage.success(`已生成出库单草稿, 源订单 ${sourceOrder.value.billNo}`)
    generateDialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error((e && e.msg) || (e && e.message) || '生成失败')
  } finally { submitting.value = false }
}

// v1.1.35: 删除出库明细行 (生成出库单弹窗内)
function removeDeliveryDetail(index) {
  deliveryForm.details.splice(index, 1)
}

// v1.1.38: 查看该订单已关联的出库单 (追溯)
async function onViewLinkedDeliveries(row) {
  linkedOrderId.value = row.id
  linkedSourceOrder.value = row
  linkedDeliveryDialogVisible.value = true
  await reloadLinkedDeliveries()
}

async function reloadLinkedDeliveries() {
  if (!linkedOrderId.value) return
  linkedDeliveryLoading.value = true
  try {
    const r = await salDeliveryApi.pageByOrderId(linkedOrderId.value, { pageNum: 1, pageSize: 50 })
    linkedDeliveries.value = r.data?.records || []
  } catch (e) {
    ElMessage.error('加载关联出库单失败: ' + (e.message || '未知错误'))
  } finally { linkedDeliveryLoading.value = false }
}

function jumpToDelivery(deliveryId) {
  router.push({ path: '/sales/delivery', query: { id: deliveryId } })
}

// v1.1.41: 查看订单发货详情 (已发/未发数量)
async function onViewDeliverySummary(row) {
  deliverySummaryOrderId.value = row.id
  deliverySummaryOrder.value = row
  deliverySummaryDialogVisible.value = true
  await reloadDeliverySummary()
}

async function reloadDeliverySummary() {
  if (!deliverySummaryOrderId.value) return
  deliverySummaryLoading.value = true
  try {
    const r = await salOrderApi.getDeliverySummary(deliverySummaryOrderId.value)
    deliverySummaryData.value = r.data || []
  } catch (e) {
    ElMessage.error('加载发货详情失败: ' + (e.message || '未知错误'))
  } finally { deliverySummaryLoading.value = false }
}

function addDetail() {
  if (taxSeparation.value === 'true') {
    form.value.details.push({ productId: null, productCode: '', productName: '', qty: null, price: 0, taxRate: 13 })
  } else {
    form.value.details.push({ productId: null, productCode: '', productName: '', qty: null, price: 0 })
  }
}

async function onProductChange(row) {
  const p = products.value.find(x => x.id === row.productId)
  if (!p) return
  row.productCode = p.productCode
  row.productName = p.productName
  // v1.1.38+: 自动带出 规格/型号/色号/单位 (打印预览用, 之前只带 productCode/productName, 规格列空白)
  if (p.spec != null)   row.spec = p.spec
  if (p.model != null)  row.model = p.model
  if (p.colorNo != null) row.colorNo = p.colorNo
  // 单位: 优先从商品主单位 id 带出, name 由后端 service 在 detail() 时注入
  if (!row.unitId && p.mainUnitId != null) row.unitId = p.mainUnitId
  // 优先取该客户对此商品的上次出库单价
  if (form.value.customerId && row.productId) {
    try {
      const res = await salOrderApi.getLastPrice(form.value.customerId, row.productId)
      if (res.data > 0) { row.price = res.data; return }
    } catch (e) { /* ignore */ }
  }
  row.price = p.salePrice || 0
}

async function onSubmit() {
  if (!form.value.customerId) { ElMessage.warning('请选择客户'); return }
  if (!form.value.details.length) { ElMessage.warning('请添加商品明细'); return }
  submitting.value = true
  try {
    // v1.1.19+: 含税单价. totalAmount = totalAmountTax = 开单金额, 不再算税.
    let totalQty = 0, totalAmount = 0
    form.value.details.forEach(d => {
      totalQty += d.qty || 0
      totalAmount += (d.qty || 0) * (d.price || 0)
    })
    form.value.totalQty = totalQty
    form.value.totalAmount = totalAmount
    form.value.taxAmount = 0
    form.value.totalAmountTax = totalAmount

    if (form.value.id) {
      await salOrderApi.update(form.value)
    } else {
      await salOrderApi.add(form.value)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}

onMounted(loadData)

// ==================== v1.1.36: 打印 ====================
// 浏览器打印 (myprint-design)
const { doPrint } = usePrint()
// v1.1.38: 打印 header/detail map 对齐销售出库单 (SAL_DELIVERY)
const SAL_ORDER_HEADER_MAP = {
  billNo: 'billNo',
  billDate: 'billDate',
  customerName: 'customerName',
  warehouseName: 'warehouseName',
  address: 'address',
  phone: 'phone',
  // v1.1.38+: 交货方式/付款方式 取中文 label
  deliveryMethod: 'deliveryMethodLabel',
  payType: 'payTypeLabel',
  totalQty: 'totalQty',
  totalAmount: 'totalAmount',
  remark: 'remark'
}
const SAL_ORDER_DETAIL_MAP = {
  lineNo: 'lineNo',
  productCode: 'productCode',
  productName: 'productName',
  model: 'pModel',
  colorNo: 'pColorNo',
  spec: 'spec',
  unitName: 'unitName',
  qty: 'qty',
  price: 'price',
  amount: 'amount',
  taxRate: 'taxRate',
  batchNo: 'batchNo',
  poNo: 'poNo',
  locationName: 'locationName'
}
async function onPrint(row) {
  try {
    const r = await salOrderApi.detail(row.id)
    await doPrint({
      bizType: BIZ_TYPES.SAL_ORDER,
      bill: r.data || {},
      fieldMap: SAL_ORDER_HEADER_MAP,
      detailsKey: 'details',
      detailFieldMap: SAL_ORDER_DETAIL_MAP,
      customerId: r.data?.customerId || row.customerId || undefined
    })
  } catch (e) {
    ElMessage.error(e.message || '打印失败')
  }
}

// 飞鹅云打印
const feiePreviewVisible = ref(false)
const feiePreviewLoading = ref(false)
const feiePreviewHtml = ref('')
const feiePrinting = ref(false)
let feiePendingBillId = null
const feieCurrentBizType = ref('SAL_ORDER')

async function feiePreview(row) {
  feiePreviewLoading.value = true
  try {
    feiePendingBillId = row.id
    feiePreviewHtml.value = ''
    feiePreviewVisible.value = true
    const r = await feiePrintApi.preview('SAL_ORDER', row.id)
    feiePreviewHtml.value = r.data || ''
  } catch (e) {
    ElMessage.error('飞鹅预览失败: ' + (e.message || '未知错误'))
    feiePreviewVisible.value = false
  } finally { feiePreviewLoading.value = false }
}

async function feieDirectPrint(row) {
  feiePreview(row) // 先弹预览弹窗, 让用户可看一眼再确认打印
}

async function feieConfirmPrint() {
  if (!feiePendingBillId) return
  feiePrinting.value = true
  try {
    const res = await feiePrintApi.print('SAL_ORDER', feiePendingBillId)
    ElMessage.success(res.msg || '打印成功')
    feiePreviewVisible.value = false
  } catch (e) {
    ElMessage.error('飞鹅打印失败: ' + (e.message || '未知错误'))
  } finally { feiePrinting.value = false }
}

function onPrintCommand(cmd, row) {
  if (cmd === 'browser') return onPrint(row)
  if (cmd === 'feie-preview' || cmd === 'feie-print') {
    feieCurrentBizType.value = 'SAL_ORDER'
    return cmd === 'feie-preview' ? feiePreview(row) : feieDirectPrint(row)
  }
}
</script>

<style scoped>
.toolbar { margin-bottom: 12px; }
.pager { margin-top: 12px; text-align: right; }
</style>
