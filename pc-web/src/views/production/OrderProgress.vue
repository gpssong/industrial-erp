<template>
  <div>
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">MES 生产 / 工单报工</div>
        <h1 class="xk-title">工单进度与批次追溯</h1>
      </div>
      <div class="xk-heading-actions">
        <span class="xk-badge xk-badge-green">批次双向追溯</span>
      </div>
    </div>

    <!-- 工单进度看板 -->
    <div class="page-card" style="margin-bottom:16px">
      <div class="section-heading">
        <span>01</span>
        <div>
          <div class="card-title">工单工序进度</div>
          <div class="subtle">开单时按 BOM 展开工序, 扫码报工后实时看每单到哪道工序</div>
        </div>
      </div>
      <div class="toolbar" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div></div>
        <div style="display:flex;gap:8px">
          <el-input v-model="orderIdInput" placeholder="生产单ID" style="width:220px" clearable @keyup.enter="loadProgress" />
          <el-button type="primary" @click="loadProgress">查询</el-button>
        </div>
      </div>
      <el-empty v-if="!progress.length && loaded" description="输入生产单ID查看该单工序进度 (开单时展开)" />
      <el-table v-else :data="progress" border stripe v-loading="progLoading">
        <el-table-column prop="processNo" label="顺序" width="70" align="center" />
        <el-table-column prop="processName" label="工序" width="140" />
        <el-table-column prop="workshop" label="车间" width="140" />
        <el-table-column prop="planQty" label="计划量" width="100" align="right" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="stTag(row.status)" size="small">{{ stLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="goodQty" label="合格" width="90" align="right" />
        <el-table-column prop="lossQty" label="不良" width="90" align="right" />
        <el-table-column prop="operator" label="操作人" width="120" />
        <el-table-column label="开工时间" width="160">
          <template #default="{ row }">{{ fmt(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="完成时间" width="160">
          <template #default="{ row }">{{ fmt(row.endTime) }}</template>
        </el-table-column>
      </el-table>
      <el-progress :percentage="pct" :stroke-width="10" style="margin-top:12px" :format="() => ''" />
      <div class="muted-tip" style="margin-top:6px">工序完成度 {{ pct }}% · 共 {{ progress.length }} 道</div>
    </div>

    <!-- 批次追溯 -->
    <div class="page-card">
      <div class="section-heading">
        <span>02</span>
        <div>
          <div class="card-title">批次追溯</div>
          <div class="subtle">料 → 半成品卷 → 成品 → 客户 双向递归展开</div>
        </div>
      </div>
      <div class="toolbar" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div></div>
        <div style="display:flex;gap:8px">
          <el-input v-model="batchNoInput" placeholder="批次/卷号" style="width:240px" clearable @keyup.enter="loadTrace" />
          <el-button type="primary" @click="loadTrace">追溯</el-button>
        </div>
      </div>
      <el-alert v-if="!trace" type="info" :closable="false" show-icon style="margin-bottom:12px">
        输入批次/卷号, 递归展开它的直接上游 (用了哪些料) 与直接下游 (流到哪)。逐层点「继续上溯」直到原料批。
      </el-alert>
      <div v-else>
        <!-- Figma .trace-summary: 当前选中批次 -->
        <div class="trace-summary-card">
          <div>
            <div class="xk-eyebrow">已选批次</div>
            <div class="detail-title">{{ trace.batch ? trace.batch.batchNo : '-' }}</div>
            <div class="subtle">类型 {{ trace.batch ? trace.batch.batchType : '-' }} · 数量 {{ trace.batch ? trace.batch.qty : '-' }}</div>
          </div>
          <span class="xk-badge xk-badge-blue">{{ trace.upstream.length + trace.downstream.length }} 条链路</span>
        </div>
        <!-- Figma .timeline: 上游/下游 双向 -->
        <div class="trace-directions" style="margin-top:16px">
          <div class="trace-direction">
            <div class="trace-direction-title"><el-icon style="margin-right:5px"><Top /></el-icon> 上游 · 我用了哪些料 — {{ trace.upstream.length }} 条</div>
            <div v-if="!trace.upstream.length" class="muted-tip">无上游 (已是原料批)</div>
            <div v-for="u in trace.upstream" :key="u.id" class="trace-row">
              <el-button link type="primary" @click="loadTrace(u.parentBatchNo, 1)">{{ u.parentBatchNo }}</el-button>
              <span class="muted-tip">用量 {{ u.qty }} · {{ u.billType }}</span>
            </div>
          </div>
          <div class="trace-direction">
            <div class="trace-direction-title"><el-icon style="margin-right:5px"><Bottom /></el-icon> 下游 · 我流到哪些批次 — {{ trace.downstream.length }} 条</div>
            <div v-if="!trace.downstream.length" class="muted-tip">无下游 (已是最末端批次)</div>
            <div v-for="d in trace.downstream" :key="d.id" class="trace-row">
              <el-button link type="primary" @click="loadTrace(d.childBatchNo, 1)">{{ d.childBatchNo }}</el-button>
              <span class="muted-tip">用量 {{ d.qty }} · {{ d.billType }} {{ d.customerId ? ('· 客户'+d.customerId) : '' }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Top, Bottom } from '@element-plus/icons-vue'
import { mesApi } from '@/api/production'

const progress = ref([])
const progLoading = ref(false)
const loaded = ref(false)
const orderIdInput = ref('')
const batchNoInput = ref('')
const trace = ref(null)
const traceLoading = ref(false)

function stLabel(s) { return { 0: '未开工', 1: '进行中', 2: '完成', 3: '回退' }[s] ?? '未知' }
function stTag(s) { return { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger' }[s] ?? 'info' }
function fmt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '-' }
const pct = computed(() => {
  if (!progress.value.length) return 0
  const done = progress.value.filter(p => p.status === 2).length
  return Math.round((done / progress.value.length) * 100)
})

async function loadProgress() {
  const id = orderIdInput.value
  if (!id) { ElMessage.warning('请输入生产单ID'); return }
  progLoading.value = true
  try {
    const r = await mesApi.orderProgress(id)
    progress.value = r?.data || []
    loaded.value = true
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '查询失败')
  } finally {
    progLoading.value = false
  }
}

async function loadTrace(batchNo, depth = 0) {
  if (!batchNo) { ElMessage.warning('请输入批次/卷号'); return }
  traceLoading.value = true
  try {
    const r = await mesApi.batchTrace(batchNo, depth)
    trace.value = r?.data || null
    batchNoInput.value = batchNo
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '追溯失败')
  } finally {
    traceLoading.value = false
  }
}
</script>

<style scoped lang="scss">
.muted-tip { color:#909399;font-size:12px }
.trace-row { display:flex;align-items:center;gap:10px;padding:4px 0 }

// Figma 追溯布局
.trace-summary-card {
  display: flex; align-items: start; justify-content: space-between; gap: 10px;
  padding: 14px 18px; border: 1px solid var(--line); border-radius: 10px; background: #fbfcfe;
}
.trace-directions { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.trace-direction {
  border: 1px solid var(--line); border-radius: 10px; overflow: hidden;
  .trace-direction-title {
    padding: 10px 14px; font-size: 13px; font-weight: 700; color: var(--text);
    background: #f8fafc; border-bottom: 1px solid var(--line);
    .el-icon { color: var(--primary-color); }
  }
  .trace-row { padding: 8px 14px; border-bottom: 1px solid #f0f3f7; &:last-child { border-bottom: 0; } }
}
@media (max-width: 900px) { .trace-directions { grid-template-columns: 1fr; } }
</style>
