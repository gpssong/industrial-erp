<template>
  <div class="wo-page">
    <!-- 页面标题 (对齐 Figma) -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">MES 生产 / 工单管理</div>
        <h1 class="xk-title">生产工单</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="onExport"><el-icon><Download /></el-icon>导出生产记录</el-button>
        <el-button type="primary" @click="onNewWo"><el-icon><Plus /></el-icon>新建工单</el-button>
      </div>
    </div>

    <!-- XK 合规条 -->
    <div class="xk-compliance-strip">
      <el-icon class="xk-cs-icon"><CircleCheckFilled /></el-icon>
      <div class="xk-cs-body">
        <strong>XK 生产许可受控记录</strong>
        <small>食品袋纳入 XK16-204 食品相关产品生产许可管控 · 工业袋执行通用质量标准 · 全部生产记录禁止物理删除</small>
      </div>
      <span class="xk-cs-badge">XK 受控</span>
    </div>

    <!-- 主体: 左今日计划 + 右工单详情 -->
    <div class="wo-grid">
      <!-- 左: 今日生产计划 -->
      <div class="plan-card">
        <div class="plan-head">
          <div>
            <b>今日生产计划</b>
            <small>{{ planSummary }}</small>
          </div>
          <el-select v-model="lineFilter" size="small" style="width: 130px">
            <el-option label="全部产品线" value="all" />
            <el-option v-for="l in productLines" :key="l" :label="l" :value="l" />
          </el-select>
        </div>
        <div class="plan-list" v-loading="planLoading">
          <div
            v-for="w in filteredPlans"
            :key="w.id"
            class="plan-item"
            :class="{ active: w.id === activeWo?.id }"
            @click="onPickPlan(w)"
          >
            <span class="plan-dot" :class="dotClass(w.status)"></span>
            <div class="plan-body">
              <div class="plan-top">
                <strong>{{ w.no }}</strong>
                <small>{{ w.product }}</small>
                <div class="plan-product-line"><el-tag size="small" effect="plain">{{ w.line }}</el-tag></div>
              </div>
              <div class="plan-bottom">
                <div class="line-meta">
                  <span class="line-name">{{ w.lineNo }}</span>
                  <small>计划 {{ w.planQty }}</small>
                </div>
                <el-tag size="small" effect="light" :type="statusTag(w.status)">{{ statusLabel(w.status) }}</el-tag>
              </div>
            </div>
            <el-icon class="plan-arrow"><ArrowRight /></el-icon>
          </div>
        </div>
      </div>

      <!-- 右: 生产记录详情 -->
      <div class="wo-detail">
        <div class="wo-detail-head">
          <div class="d-subtitle">
            <span class="d-title-line">生产记录 · {{ detail.no || '未选择' }}</span>
          </div>
          <div class="wo-detail-head-right">
            <div v-if="qrDataUrl" class="wo-qr" title="生产记录号二维码 · 扫码可快速定位/追溯本工单">
              <img :src="qrDataUrl" alt="生产记录号二维码" />
              <small>{{ detail.no }}</small>
            </div>
            <el-tag :type="detail.status === '已作废' ? 'danger' : 'success'" size="small" effect="light">
              {{ detail.status || '—' }}
            </el-tag>
          </div>
        </div>

        <h3 class="wo-detail-name">{{ detail.productName || '请选择工单' }}</h3>

        <!-- 工单主信息 8 格 -->
        <div class="wo-info-grid">
          <div class="info-cell">
            <small>工单编号</small>
            <b>{{ detail.no }}</b>
            <span class="info-tag blue">系统生成</span>
          </div>
          <div class="info-cell">
            <small>生产时间</small>
            <b>{{ detail.startTime }}</b>
            <span class="info-tag gray">不可修改</span>
          </div>
          <!-- v1.1.102 开工操作人 (吹膜工在 App 端点开工时留痕) -->
          <div class="info-cell">
            <small>开工操作人</small>
            <b>{{ detail.releaseName || '—' }}</b>
            <span v-if="detail.releaseName" class="info-tag blue">吹膜工</span>
          </div>
          <div class="info-cell">
            <small>产品类别</small>
            <b>{{ detail.line }}</b>
            <span class="info-tag gray">质量管控</span>
          </div>
          <div class="info-cell">
            <small>执行标准</small>
            <b>{{ detail.standard }}</b>
          </div>
          <!-- v1.1.100 食品级成品回显备案编号/用途/限值 (从 base_product 带出) -->
          <template v-if="detail.isFoodGrade">
            <div class="info-cell">
              <small>备案编号</small>
              <b>{{ detail.foodFilingNo || '—' }}</b>
            </div>
            <div class="info-cell">
              <small>食品级用途</small>
              <b>{{ detail.foodUseDesc || '—' }}</b>
            </div>
            <div class="info-cell">
              <small>总迁移量限值</small>
              <b v-if="detail.foodMigrationLimit != null">{{ detail.foodMigrationLimit }} mg/dm²</b>
              <b v-else>—</b>
            </div>
          </template>
          <div class="info-cell">
            <small>设备编号</small>
            <b>{{ detail.lineNo }}</b>
          </div>
          <div class="info-cell">
            <small>生产数量</small>
            <b>{{ detail.planQty }}</b>
          </div>
          <div class="info-cell">
            <small>当班人员</small>
            <b>{{ detail.crew }}</b>
          </div>
          <div class="info-cell">
            <small>当班班次</small>
            <b>{{ detail.shift }}</b>
          </div>
        </div>

        <!-- v1.1.99: 成品规格属性 7 字段 (长度/宽度/厚度/克重/规格/型号/色号) -->
        <div class="wo-prod-spec">
          <div class="spec-head">
            <el-icon><Box /></el-icon>
            <span>成品规格属性</span>
            <small>从商品库自动带出 · 与生产要求不一致时, 请编辑商品主数据</small>
          </div>
          <div class="spec-grid">
            <div class="spec-cell"><small>长度 (mm)</small><b>{{ detail.prodLength }}</b></div>
            <div class="spec-cell"><small>宽度 (mm)</small><b>{{ detail.prodWidth }}</b></div>
            <div class="spec-cell"><small>厚度 (mm)</small><b>{{ detail.prodDensity }}</b></div>
            <div class="spec-cell"><small>克重 (g/个)</small><b>{{ detail.prodGramWeight }}</b></div>
            <div class="spec-cell"><small>规格</small><b>{{ detail.prodSpec }}</b></div>
            <div class="spec-cell"><small>型号</small><b>{{ detail.prodModel }}</b></div>
            <div class="spec-cell"><small>色号</small><b>{{ detail.prodColorNo }}</b></div>
          </div>
        </div>

        <!-- BOM 自动匹配与原料领用 -->
        <div class="wo-bom">
          <div class="wo-bom-head">
            <b>BOM 自动匹配与原料领用</b>
          </div>
          <div class="wo-bom-list">
            <div v-for="b in detail.materials" :key="b.id" class="wo-bom-row">
              <span class="bom-icon"><el-icon><Box /></el-icon></span>
              <div class="bom-body">
                <strong>{{ b.no }}</strong>
                <small>{{ b.name }} · 领用 {{ b.qty }} · {{ b.batch }}</small>
              </div>
              <el-tag type="success" size="small" effect="light">已出库</el-tag>
            </div>
            <div v-if="!detail.materials.length" class="wo-bom-empty">该工单暂无已展开的 BOM 领料明细</div>
            <div v-if="detail.autoGenerated" class="wo-bom-auto">
              <el-icon class="auto-ic"><Check /></el-icon>
              <div class="auto-body">
                <b>领料单 {{ detail.autoGenerated }} 已生成</b>
                <small>按 BOM 自动匹配合格批次并完成原料出库 · 对应库存流水已写入。</small>
              </div>
              <el-button type="primary" circle size="small"><el-icon><Tools /></el-icon></el-button>
            </div>
          </div>
        </div>

        <!-- 工单 footer -->
        <div class="wo-foot">
          <div class="wo-foot-left">
            <el-icon><Clock /></el-icon>
            <span>最近更新: 质量 QA · {{ detail.updatedAt }}</span>
          </div>
          <div class="wo-foot-actions">
            <el-button plain @click="onLog"><el-icon><Clock /></el-icon>查看修改日志</el-button>
            <el-button
              v-if="woDetail && woDetail.billStatus === 'DRAFT' && userStore.hasPerm('production:order:edit')"
              type="primary" plain @click="onEditWo">
              <el-icon><Edit /></el-icon>编辑
            </el-button>
            <el-button
              v-if="woDetail && woDetail.billStatus === 'DRAFT' && userStore.hasPerm('production:order:delete')"
              type="danger" plain @click="onDeleteWo">
              <el-icon><Delete /></el-icon>删除
            </el-button>
            <el-button
              v-if="woDetail && woDetail.billStatus !== 'CLOSED'"
              type="warning" plain @click="onVoid">
              <el-icon><CircleClose /></el-icon>标记作废
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 新建工单弹窗 (v1.1.99: 成品/单位/仓库/开工时间/原料批号, 食品级成品原料批号必填) -->
  <el-dialog v-model="newDialogVisible" :title="editingId ? '编辑生产工单' : '新建生产工单'" width="900px" :destroy-on-close="true" @closed="onNewDialogClosed">
    <el-form :model="newForm" label-width="100px" :rules="newFormRules" ref="newFormRef">
      <!-- 成品选择 -->
      <el-form-item label="成品" prop="productId">
        <el-select v-model="newForm.productId" filterable clearable remote
          :remote-method="searchProducts" :loading="productSearchLoading"
          @change="onProductChange"
          placeholder="输入编码/名称搜索产品" style="width:100%">
          <el-option v-for="p in productOptions" :key="p.id"
            :label="`${p.productCode} · ${p.productName}${p.spec ? ' / ' + p.spec : ''}`"
            :value="p.id" />
        </el-select>
      </el-form-item>

      <!-- 产品基本信息 (选完自动填充, 只读) -->
      <el-row :gutter="12">
        <el-col :span="8"><el-form-item label="BOM"><el-input v-model="productInfo.bomName" readonly placeholder="选择成品后自动带出" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="规格"><el-input v-model="productInfo.spec" readonly /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="型号"><el-input v-model="productInfo.model" readonly /></el-form-item></el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="8"><el-form-item label="长度(mm)"><el-input v-model="productInfo.thickness" readonly /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="宽度(mm)"><el-input v-model="productInfo.width" readonly /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="厚度"><el-input v-model="productInfo.density" readonly /></el-form-item></el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="8"><el-form-item label="克重(g/㎡)"><el-input v-model="productInfo.gramWeight" readonly /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="材质"><el-input v-model="productInfo.material" readonly /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="色号"><el-input v-model="productInfo.colorNo" readonly /></el-form-item></el-col>
      </el-row>

      <el-divider content-position="left">工单参数</el-divider>

      <!-- 工单参数 -->
      <el-row :gutter="12">
        <el-col :span="8"><el-form-item label="计划数量" prop="planQty"><el-input v-model.number="newForm.planQty" type="number" :min="1" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="单位" prop="unitId">
          <el-select v-model="newForm.unitId" filterable placeholder="选择单位" style="width:100%">
            <el-option v-for="u in unitOptions" :key="u.id" :label="u.unitName + (u.isMain === 1 ? ' (主)' : '')" :value="u.unitId" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="开工时间" prop="startDate">
          <el-date-picker v-model="newForm.startDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item></el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="12"><el-form-item label="车间"><el-input v-model="newForm.workshop" placeholder="如 一车间" /></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="班组长"><el-input v-model="newForm.leader" placeholder="现场负责人" /></el-form-item></el-col>
      </el-row>

      <!-- 食品级原料批号 (条件显示) -->
      <template v-if="productInfo.isFoodGrade === 1">
        <el-divider content-position="left">
          <el-icon style="color:#e6a23c"><Warning /></el-icon> 食品级原料批号 (XK16-204 必填)
        </el-divider>
        <el-form-item label="原料批号" prop="batchNo">
          <el-input v-model="newForm.batchNo" placeholder="如 RAW-20251014-001" maxlength="64" show-word-limit />
          <div class="form-hint">食品级成品入库触发 XK 食品接触原料追溯, 必须填写原料批号</div>
        </el-form-item>
      </template>

      <el-form-item label="备注"><el-input v-model="newForm.remark" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="可选: 工艺要点/排产要求" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="newDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="newSubmitting" @click="onConfirmNewWo">{{ editingId ? '保存修改' : '保存草稿' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Download, Plus, CircleCheckFilled, ArrowRight, Warning,
  Box, Check, Tools, Clock, CircleClose, Edit, Delete
} from '@element-plus/icons-vue'
import { prdOrderApi, bomApi } from '@/api/production'
import { productApi, warehouseApi, unitApi } from '@/api/base'
import { useUserStore } from '@/store/user'
import QRCode from 'qrcode'

const router = useRouter()
const userStore = useUserStore()

// v1.1.99 hotfix: 新建工单弹窗 state (替换原 placeholder onNewWo)
const newDialogVisible = ref(false)
const newSubmitting = ref(false)
const newFormRef = ref()
// v1.1.103+: editingId 区分新建 vs 编辑 (null=新建, 非空=编辑该工单 id)
const editingId = ref(null)
const productOptions = ref([])
const productSearchLoading = ref(false)
const productInfo = ref({})  // 选完成品后回填, 含 isFoodGrade 决定是否显示批号
const unitOptions = ref([])
const fgWarehouses = ref([])
const newForm = ref({
  productId: null, planQty: 1,
  unitId: null, warehouseId: null,
  startDate: new Date().toISOString().slice(0, 10),  // 默认今日
  workshop: '', leader: '', batchNo: '', remark: ''
})
const newFormRules = {
  productId: [{ required: true, message: '请选择成品', trigger: 'change' }],
  planQty: [{ required: true, message: '请输入计划数量', trigger: 'blur' }],
  unitId: [{ required: true, message: '请选择单位', trigger: 'change' }],
  startDate: [{ required: true, message: '请选择开工时间', trigger: 'change' }],
  batchNo: [{ required: false, validator: (rule, val, cb) => {
    if (productInfo.value.isFoodGrade === 1 && !val) cb(new Error('食品级成品原料批号必填 (XK16-204)'))
    else cb()
  }, trigger: 'blur' }]
}
let productSearchTimer = null
async function searchProducts(kw) {
  if (productSearchTimer) clearTimeout(productSearchTimer)
  productSearchTimer = setTimeout(async () => {
    if (!kw || !kw.trim()) { productOptions.value = []; return }
    productSearchLoading.value = true
    try {
      const r = await productApi.page({ pageNum: 1, pageSize: 20, keyword: kw.trim() })
      productOptions.value = (r.data && r.data.records) || []
    } catch (e) { /* 静默 */ }
    finally { productSearchLoading.value = false }
  }, 250)
}
// 选完成品 → 拉详情填表 + 检查 isFoodGrade
async function onProductChange(pid) {
  productInfo.value = {}
  newForm.value.unitId = null
  unitOptions.value = []
  if (!pid) return
  try {
    const r = await productApi.detail(pid)
    const d = r.data
    const p = d.product || {}
    // v1.1.103+: 后端 BaseProductService.detail 不返回 bomName, 这里主动拉 PrdBom 补全
    // 否则只显示 "配方 #2077..." 19位雪花 id, 用户看不出是哪个 BOM
    let bomLabel = '-'
    if (p.bomId) {
      try {
        const br = await bomApi.detail(p.bomId)
        const bom = br.data || {}
        bomLabel = `${bom.bomCode || ''} ${bom.bomName || ''}`.trim() || `配方 #${p.bomId}`
      } catch (e) { bomLabel = `配方 #${p.bomId}` }
    }
    productInfo.value = {
      bomName: bomLabel,
      spec: p.spec || '-',
      model: p.model || '-',
      thickness: p.thickness != null ? p.thickness : '-',
      width: p.width != null ? p.width : '-',
      density: p.density != null ? p.density : '-',
      gramWeight: p.gramWeight != null ? p.gramWeight : '-',
      material: p.material || '-',
      colorNo: p.colorNo || '-',
      isFoodGrade: p.isFoodGrade || 0,
      // v1.1.100 食品合规 4 字段从 base_product 带出 (食品级成品回显)
      foodExecStd: p.foodExecStd || '',
      foodFilingNo: p.foodFilingNo || '',
      foodUseDesc: p.foodUseDesc || '',
      foodMigrationLimit: p.foodMigrationLimit != null ? p.foodMigrationLimit : ''
    }
    // 主单位置顶
    const units = (d.units || []).slice().sort((a, b) => (b.isMain || 0) - (a.isMain || 0))
    unitOptions.value = units
    if (units.length && units[0].unitId) newForm.value.unitId = units[0].unitId
  } catch (e) {
    ElMessage.error('加载成品详情失败')
  }
}
// 加载成品仓 (默认下拉, warehouseType=FG)
async function loadFgWarehouses() {
  try {
    const r = await warehouseApi.list()
    const list = (r.data || []).filter(w => w.warehouseType === 'FG' && w.status === 1)
    fgWarehouses.value = list
    if (list.length && !newForm.value.warehouseId) newForm.value.warehouseId = list[0].id
  } catch (e) { /* 静默 */ }
}
async function onConfirmNewWo() {
  if (!newFormRef.value) return
  try { await newFormRef.value.validate() } catch (e) { return }
  newSubmitting.value = true
  try {
    // 单位名/仓库名快照 (后端 service 不会回填,前端带过去)
    const u = unitOptions.value.find(x => x.unitId === newForm.value.unitId)
    const w = fgWarehouses.value.find(x => x.id === newForm.value.warehouseId)
    const payload = {
      ...newForm.value,
      unitName: u ? u.unitName : '',
      warehouseName: w ? w.warehouseName : ''
    }
    if (editingId.value) {
      // v1.1.103+: 编辑模式, 显式带 id 走 update (后端 PrdOrderController.update 走非空字段)
      payload.id = editingId.value
      await prdOrderApi.update(payload)
      ElMessage.success(`工单 ${woDetail.value?.billNo || ''} 修改成功`)
    } else {
      const r = await prdOrderApi.add(payload)
      ElMessage.success(`工单 ${r.data || '已创建'} 保存为草稿, 待审核后开工`)
    }
    newDialogVisible.value = false
    loadPlans()  // 刷新今日计划
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || (editingId.value ? '保存修改失败' : '保存失败'))
  } finally {
    newSubmitting.value = false
  }
}
function onNewDialogClosed() {
  // v1.1.103+: 关闭弹窗时清空 editingId, 回到新建模式
  editingId.value = null
  newForm.value = {
    productId: null, planQty: 1, unitId: null, warehouseId: fgWarehouses.value[0]?.id || null,
    startDate: new Date().toISOString().slice(0, 10),
    workshop: '', leader: '', batchNo: '', remark: ''
  }
  productOptions.value = []
  productInfo.value = {}
  unitOptions.value = []
}

// v1.1.103+: 编辑工单 — 拉详情灌进 newForm, 弹窗改编辑模式
async function onEditWo() {
  if (!woDetail.value?.id) {
    ElMessage.warning('请先选择要编辑的工单')
    return
  }
  if (woDetail.value.billStatus !== 'DRAFT') {
    ElMessage.warning(`仅 DRAFT 状态工单可编辑, 当前状态: ${woDetail.value.billStatus || '未知'}`)
    return
  }
  if (!userStore.hasPerm('production:order:edit')) {
    ElMessage.error('无编辑权限, 请联系管理员')
    return
  }
  editingId.value = woDetail.value.id
  // 灌 form (后端 detail 已含 productName/spec/model 等冗余字段, 不需重拉)
  newForm.value = {
    productId: woDetail.value.productId,
    planQty: woDetail.value.planQty,
    unitId: woDetail.value.unitId,
    warehouseId: woDetail.value.warehouseId,
    startDate: woDetail.value.startDate ? String(woDetail.value.startDate).slice(0, 10) : new Date().toISOString().slice(0, 10),
    workshop: woDetail.value.workshop || '',
    leader: woDetail.value.leader || '',
    batchNo: woDetail.value.batchNo || '',
    remark: woDetail.value.remark || ''
  }
  // 选完 productId 后用 onProductChange 拉商品详情 + 单位下拉 + 食品级判定
  await onProductChange(woDetail.value.productId)
  // 仓库默认选第一个成品仓 (loadFgWarehouses 会自动)
  await loadFgWarehouses()
  if (woDetail.value.warehouseId) {
    newForm.value.warehouseId = woDetail.value.warehouseId
  }
  newDialogVisible.value = true
}

// v1.1.103+: 删除工单 — DRAFT 才允许, 二次确认
async function onDeleteWo() {
  const d = woDetail.value
  if (!d?.id) {
    ElMessage.warning('请先选择要删除的工单')
    return
  }
  if (d.billStatus !== 'DRAFT') {
    ElMessage.warning(`仅 DRAFT 状态工单可删除, 当前状态: ${d.billStatus || '未知'} (已审核/已开工/已完工请用「标记作废」)`)
    return
  }
  if (!userStore.hasPerm('production:order:delete')) {
    ElMessage.error('无删除权限, 请联系管理员')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除工单 ${d.billNo} 吗? 删除后不可恢复 (草稿状态可删, 已审核/已开工请用「标记作废」)`,
      '删除生产工单',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch (e) { return }
  try {
    await prdOrderApi.delete(d.id)
    ElMessage.success(`工单 ${d.billNo} 已删除`)
    // 删后清空右侧详情 + 重新拉今日计划
    woDetail.value = null
    activeWo.value = null
    loadPlans()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '删除失败')
  }
}
// 弹窗打开时确保仓库列表已加载
watch(newDialogVisible, (v) => { if (v) loadFgWarehouses() })

// 今日生产计划: 真数据 (prdOrderApi.page, billDate=today); 未加载时为空
const plans = ref([])
const activeWo = ref(null) // 当前选中的生产工单 (真数据)
const woDetail = ref(null) // 选中工单的详情 (prdOrderApi.detail 返回, 含 BOM 物料)
const lineFilter = ref('all')
const planLoading = ref(false)

// 产品线候选: 从 plans 提取去重 (按 workshop 归类; 食品袋 / 工业袋 等)
const productLines = computed(() => {
  const set = new Set()
  plans.value.forEach(w => w.line && set.add(w.line))
  return [...set]
})

const filteredPlans = computed(() =>
  lineFilter.value === 'all' ? plans.value : plans.value.filter(w => w.line === lineFilter.value)
)

const planSummary = computed(() => {
  const counts = {}
  plans.value.forEach(w => { const k = w.line || '未分线'; counts[k] = (counts[k] || 0) + 1 })
  return Object.entries(counts).map(([k, v]) => `${k} ${v} 单`).join(' · ') || '今日暂无生产计划'
})

// 真数据: 加载今日生产工单 (billDate=today, 按 workshop 归产品线)
async function loadPlans() {
  planLoading.value = true
  try {
    const today = new Date().toISOString().slice(0, 10)
    const r = await prdOrderApi.page({ pageNum: 1, pageSize: 50, billDate: today })
    const recs = (r.data?.records || []).map(o => ({
      id: o.id,
      no: o.billNo,
      product: o.productName || o.productCode || '-',
      line: o.workshop || '未分线',
      lineNo: o.workshop ? `吹膜线 · ${o.workshop}` : '未分线',
      planQty: o.planQty != null ? Number(o.planQty).toLocaleString('en-US') + ' ' + (o.unitName || 'kg') : '-',
      status: statusFromBill(o.billStatus)
    }))
    plans.value = recs
    // 默认选中第一个未完工工单
    if (!activeWo.value?.id && recs.length) activeWo.value = recs.find(w => w.status !== '已完成' && w.status !== '已作废') || recs[0]
  } catch (e) {
    // 后端无数据 / 网络异常时保留空态 (Figma 可降级为空列表)
    plans.value = []
  } finally {
    planLoading.value = false
  }
}

function statusFromBill(b) {
  // PRD_ORDER 状态机: DRAFT/RELEASED/PRODUCING/FINISHED/CLOSED → WO 视角标签
  const map = {
    DRAFT: '待开工', RELEASED: '生产中', PRODUCING: '生产中',
    FINISHED: '已完成', CLOSED: '已作废'
  }
  return map[b] || '生产中'
}

function dotClass(status) {
  if (status === '生产中') return 'green'
  if (status === '待开工') return 'blue'
  if (status === '已完成') return 'gray'
  if (status === '已作废') return 'red'
  return 'gray'
}
function statusTag(status) {
  if (status === '生产中') return 'success'
  if (status === '待开工') return ''
  if (status === '已完成') return 'info'
  if (status === '已作废') return 'danger'
  return 'info'
}
function statusLabel(s) { return s }

// 选中工单时拉详情 (含 BOM 展开的领料物料)
function onPickPlan(w) { activeWo.value = w }

// v1.1.116: activeWo 一变就拉详情。
// 之前只有 onPickPlan (点击列表行) 才拉, 而 loadPlans() 的「默认选中第一个未完工工单」
// 是直接给 activeWo 赋值, 绕过了 onPickPlan → woDetail 一直是 null,
// 右侧详情卡 (成品规格属性 7 字段 / 生产时间 / 当班人员 / BOM 领料) 全部回落成 '—'。
// 截图里还能看到兜底痕迹: 「领料单 PD202610200002-01 已生成」是 w.no.replace 拼的, 不是真领料单号。
async function loadWoDetail(id) {
  if (!id) { woDetail.value = null; return }
  woDetail.value = null
  try {
    const r = await prdOrderApi.detail(id)
    woDetail.value = r.data || {}
  } catch (e) {
    woDetail.value = {}
    console.warn('[production/order] 工单详情加载失败:', (e && (e.msg || e.message)) || e)
  }
}
watch(activeWo, (w) => { loadWoDetail(w && w.id) })

// 右栏展示用: 把真数据 + 选中行拼成 Figma 8 格 + BOM 面板的字段
const detail = computed(() => {
  const w = activeWo.value || {}
  const d = woDetail.value || {}
  const line = w.line === '食品袋' ? '食品袋' : (d.workshop ? '工业袋' : '工业袋')
  return {
    no: w.no,
    productName: d.productName || w.product,
    startTime: d.createTime ? String(d.createTime).replace('T', ' ') : '—',
    line,
    standard: (d.pFoodExecStd && d.pFoodExecStd.trim())
      ? d.pFoodExecStd
      : (line === '食品袋' ? 'GB 4806.7 食品接触材料' : '企业工业包装质量标准'),
    // v1.1.100 食品合规 (PrdOrderService.detail 从 base_product 注入, 食品级成品回显)
    foodFilingNo: d.pFoodFilingNo || '',
    foodUseDesc: d.pFoodUseDesc || '',
    foodMigrationLimit: d.pFoodMigrationLimit != null ? d.pFoodMigrationLimit : null,
    isFoodGrade: d.pIsFoodGrade === 1,
    lineNo: w.lineNo,
    planQty: w.planQty,
    crew: d.leader || '—',
    releaseName: d.releaseName || '',  // v1.1.102 开工操作人 (吹膜工留痕)
    shift: '早班 08:00–16:00',
    status: w.status,
    updatedAt: d.updateTime ? String(d.updateTime).replace('T', ' ') : '—',
    // BOM 物料 (PrdOrderService.detail 展开的领料明细)
    materials: (d.requisitionDetails || d.bomDetails || []).map((m, i) => ({
      id: i,
      no: m.requisitionNo || m.billNo || `RM-${String(i + 1).padStart(3, '0')}`,
      name: m.productName || m.materialName || m.productCode || '原料',
      qty: m.qty != null ? Number(m.qty).toLocaleString('en-US') + (m.unitName ? ' ' + m.unitName : '') : '-',
      batch: m.batchNo || '-'
    })),
    // v1.1.116: 只认真实领料单号。原来兜底拼 w.no.replace('WO-','LL-')+'-01',
    // 但工单号是 PD202610200002 (没有 'WO-' 前缀), replace 原样返回 → 拼出
    // 「领料单 PD202610200002-01 已生成 · 对应库存流水已写入」这种不存在单据的假陈述。
    // 删掉兜底: 没领料单就是不显示, 上面 materials 的空态已经说明了。
    autoGenerated: d.requisitionNo || '',
    // v1.1.99: 成品属性 7 字段 (PrdOrderService.detail 注入 base_product transient)
    prodSpec: d.spec || '—',
    prodModel: d.model || '—',
    prodLength: d.pThickness != null ? d.pThickness : '—',  // 长度 = thickness (产品库别名)
    prodWidth: d.pWidth != null ? d.pWidth : '—',
    prodDensity: d.pDensity != null ? d.pDensity : '—',     // 厚度
    prodGramWeight: d.pGramWeight != null ? d.pGramWeight : '—',
    prodColorNo: d.pColorNo || '—'
  }
})

// v1.1.120: 生产记录号二维码 (扫该码 = 快速定位/追溯这张工单). 浏览器版 qrcode 库生成 dataURL
// 直接 <img> 渲染, 不引 myprint (那是打印链路专用). detail.no 变化即重算 (放在 detail 定义后避免 TDZ).
const qrDataUrl = ref('')
watch(
  () => detail.value && detail.value.no,
  async (no) => {
    if (!no) { qrDataUrl.value = ''; return }
    try {
      qrDataUrl.value = await QRCode.toDataURL(no, {
        width: 132,
        margin: 1,
        color: { dark: '#0f2846', light: '#ffffff' }
      })
    } catch (e) {
      qrDataUrl.value = ''  // 生成失败就隐藏, 不报错
    }
  },
  { immediate: true }
)

function onExport() { ElMessage.info('导出生产记录接入中') }
function onNewWo() {
  // v1.1.99 hotfix: 原 v1.1.80 placeholder router.push('/production/order') 是同路径, 被 Vue Router 静默忽略 (NavigationDuplicated)
  // 现改为弹新建工单弹窗, 走 prdOrderApi.add
  newDialogVisible.value = true
}
function onLog() { router.push('/xk/audit') }
async function onVoid() {
  if (!activeWo.value?.id) return
  // 作废 = billStatus → CLOSED (已作废), XK 合规禁止物理删除, 只改状态留痕
  try {
    await ElMessageBox.confirm(
      `确定作废工单 ${activeWo.value.no} 吗? 作废后工单状态将变为「已作废」, 不可再开工/完工, 且不可撤销。`,
      '作废生产工单',
      { type: 'warning', confirmButtonText: '确认作废', cancelButtonText: '取消' }
    )
  } catch (e) { return }
  try {
    await prdOrderApi.void(activeWo.value.id)
    ElMessage.success(`工单 ${activeWo.value.no} 已作废`)
    loadPlans()  // 刷新列表状态
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '作废失败')
  }
}

onMounted(loadPlans)
</script>

<style scoped lang="scss">
.wo-page { display: flex; flex-direction: column; gap: 16px; }

.wo-grid {
  display: grid;
  grid-template-columns: 360px 1fr;
  gap: 20px;
  align-items: start;
}
@media (max-width: 1100px) {
  .wo-grid { grid-template-columns: 1fr; }
}

/* 左: 今日生产计划 */
.plan-card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 18px 20px;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.plan-head {
  display: flex; align-items: center; justify-content: space-between;
  gap: 10px; margin-bottom: 12px;
}
.plan-head b { font-size: 15px; font-weight: 730; color: var(--text); }
.plan-head small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }

.plan-list { display: flex; flex-direction: column; gap: 8px; }
.plan-item {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 14px;
  background: #fbfcfe;
  border: 1px solid #eef2f7;
  border-radius: 11px;
  cursor: pointer;
  transition: all .18s;
  &:hover { background: #f4f8fd; border-color: #cfe0f2; }
  &.active {
    border-color: var(--primary-color);
    background: #f4f8fd;
    box-shadow: 0 8px 24px rgba(22, 113, 217, .12);
    .plan-arrow { color: var(--primary-color); }
  }
}
.plan-dot {
  width: 8px; height: 8px; border-radius: 50%;
  flex: 0 0 auto;
  &.green { background: #2ca767; }
  &.blue  { background: var(--primary-color); }
  &.gray  { background: #b6c2d0; }
  &.red   { background: var(--danger); }
}
.plan-body { flex: 1; min-width: 0; }
.plan-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.plan-top strong { font-size: 13px; font-weight: 700; color: var(--text); }
.plan-top small { font-size: 11px; color: var(--muted); }
.plan-product-line { margin-left: auto; }
.plan-bottom {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 8px;
}
.line-meta { display: flex; align-items: center; gap: 8px; font-size: 11px; color: #526174; }
.line-meta .line-name { font-weight: 650; }
.line-meta small { color: var(--muted); }

.plan-arrow { color: #b6c2d0; flex: 0 0 auto; }

/* 右: 生产记录详情 */
.wo-detail {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 20px 22px;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.wo-detail-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 12px;
}
.d-subtitle .d-title-line { font-size: 13px; color: var(--primary-color); font-weight: 650; }
/* v1.1.120: 右侧 = 生产记录号二维码 + 状态 tag */
.wo-detail-head-right { display: flex; align-items: center; gap: 12px; }
.wo-qr {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}
.wo-qr img { width: 92px; height: 92px; display: block; image-rendering: pixelated; }
.wo-qr small { font-size: 11px; color: var(--text); font-weight: 600; letter-spacing: .2px; }

.wo-detail-name {
  font-size: 18px; font-weight: 760; color: var(--text);
  margin: 0 0 16px;
}

/* 工单主信息 8 格 */
.wo-info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 18px;
}
@media (max-width: 1100px) {
  .wo-info-grid { grid-template-columns: repeat(2, 1fr); }
}
.info-cell {
  background: #fbfcfe;
  border: 1px solid #eef2f7;
  border-radius: 9px;
  padding: 12px 14px;
  position: relative;
  display: flex; flex-direction: column; gap: 4px;
}
.info-cell small { font-size: 11px; color: var(--muted); }
.info-cell b { font-size: 14px; font-weight: 700; color: var(--text); }
.info-tag {
  position: absolute; top: 12px; right: 12px;
  font-size: 10px; font-weight: 700;
  padding: 2px 8px; border-radius: 999px;
  &.blue { background: #eef6ff; color: var(--primary-color); }
  &.gray { background: #f0f2f5; color: #64748b; }
}

/* BOM 自动匹配 */
.wo-bom {
  background: #fbfcfe;
  border: 1px solid #eef2f7;
  border-radius: 11px;
  padding: 16px;
  margin-bottom: 16px;
}
.wo-bom-head b { font-size: 13px; font-weight: 700; color: var(--text); }
.wo-bom-list { margin-top: 12px; display: flex; flex-direction: column; gap: 10px; }
.wo-bom-empty {
  font-size: 12px; color: var(--muted);
  background: #fbfcfe; border: 1px dashed #d8e0eb;
  border-radius: 8px; padding: 10px 12px;
}
.wo-bom-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 9px;
}
.bom-icon {
  width: 30px; height: 30px;
  display: grid; place-items: center;
  background: var(--primary-soft, #eef6ff);
  color: var(--primary-color);
  border-radius: 8px;
}
.bom-body { flex: 1; min-width: 0; }
.bom-body strong { display: block; font-size: 13px; font-weight: 700; color: var(--text); }
.bom-body small { display: block; font-size: 11px; color: var(--muted); margin-top: 2px; }

.wo-bom-auto {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 14px;
  background: linear-gradient(90deg, #f4f9ff, #f8fbff);
  border: 1px solid #cfe0f2;
  border-radius: 9px;
}
.auto-ic {
  width: 28px; height: 28px;
  display: grid; place-items: center;
  background: var(--primary-color); color: #fff;
  border-radius: 50%;
}
.auto-body { flex: 1; min-width: 0; }
.auto-body b { display: block; font-size: 13px; font-weight: 700; color: var(--text); }
.auto-body small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }

/* 工单 footer */
.wo-foot {
  display: flex; align-items: center; justify-content: space-between;
  padding-top: 14px;
  border-top: 1px solid var(--line);
  gap: 10px;
}
.wo-foot-left {
  display: flex; align-items: center; gap: 6px;
  font-size: 11px; color: var(--muted);
}
.wo-foot-left .el-icon { color: var(--muted); }
.wo-foot-actions { display: flex; gap: 8px; }

/* v1.1.99: 成品规格属性 7 字段 (长度/宽度/厚度/克重/规格/型号/色号) */
.wo-prod-spec { margin-top: 16px; padding: 14px 16px; background: #fafbfc; border-radius: 10px; border: 1px solid #ebeef5; }
.wo-prod-spec .spec-head { display: flex; align-items: center; gap: 6px; color: #303133; font-weight: 600; margin-bottom: 10px; font-size: 14px; }
.wo-prod-spec .spec-head small { font-weight: 400; color: #909399; font-size: 12px; margin-left: 6px; }
.wo-prod-spec .spec-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.wo-prod-spec .spec-cell { background: #fff; border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px; }
.wo-prod-spec .spec-cell small { display: block; color: #909399; font-size: 12px; margin-bottom: 2px; }
.wo-prod-spec .spec-cell b { color: #303133; font-size: 14px; font-weight: 500; word-break: break-all; }
</style>