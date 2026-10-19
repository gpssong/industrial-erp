<template>
  <div>
    <div class="page-card">
      <div class="toolbar" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div class="section-title">🛠 设备维保报表 (OEE-style, 简化版)</div>
        <div class="muted-tip">数据源: 维保记录 (workflow.maintainApi) · 按设备聚合 · 排序: 下次到期最近 5 条置顶</div>
      </div>

      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px">
        本版本只做 2 个有用指标: <b>维保次数</b> + <b>下次到期</b>。完整 OEE (可用率 × 性能 × 质量) 见 v1.1.91。
      </el-alert>

      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="equipmentCode" label="设备编号" width="140">
          <template #default="{ row }">{{ row.equipmentCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="equipmentName" label="设备名称" min-width="160">
          <template #default="{ row }">{{ row.equipmentName || row.deviceName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="workshop" label="车间" width="120">
          <template #default="{ row }">{{ row.workshop || '-' }}</template>
        </el-table-column>
        <el-table-column label="维保次数" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.count >= 5 ? 'success' : (row.count >= 1 ? 'info' : 'warning')" size="small">{{ row.count }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="平均保养周期(天)" width="160" align="center">
          <template #default="{ row }">
            <span v-if="row.avgCycle != null">{{ row.avgCycle }}</span>
            <span v-else class="muted-tip">-</span>
          </template>
        </el-table-column>
        <el-table-column label="下次到期" width="140">
          <template #default="{ row }">
            <span :class="{ overdue: isOverdue(row.nextDue) }">{{ row.nextDue || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.deviceStatusTag" size="small">{{ row.deviceStatusLabel }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!rows.length && !loading" description="暂无维保数据 (请先录入设备 + 维保记录)" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { maintainApi } from '@/api/workflow'
import { equipmentApi } from '@/api/equipment'

const loading = ref(false)
const rows = ref([])

const STATUS_MAP = {
  ACTIVE: { tag: 'success', label: '在用' },
  MAINT: { tag: 'warning', label: '维保中' },
  IDLE: { tag: 'info', label: '闲置' },
  SCRAP: { tag: 'danger', label: '报废' }
}

function isOverdue(d) {
  return d && new Date(d).getTime() < Date.now()
}

async function load() {
  loading.value = true
  try {
    // 拉全部设备 (含状态) + 全部维保记录, 前端按 deviceId group
    const [eqR, mrR] = await Promise.all([
      equipmentApi.page({ pageSize: 500 }).catch(() => null),
      maintainApi.page({ pageNum: 1, pageSize: 1000 }).catch(() => null)
    ])
    const equipments = eqR?.data?.records || []
    const maintains = mrR?.data?.records || []

    // 按 device 维度 group (deviceName 为主 key, 若设备表有对应 deviceId 优先用 id)
    const byKey = new Map()
    for (const m of maintains) {
      const key = m.deviceId ? 'id:' + m.deviceId : 'name:' + (m.deviceName || '')
      if (!byKey.has(key)) byKey.set(key, { deviceId: m.deviceId, deviceName: m.deviceName, maints: [], deviceMeta: null })
      byKey.get(key).maints.push(m)
      if (m.deviceId && !byKey.get(key).deviceMeta) {
        const eq = equipments.find(x => String(x.id) === String(m.deviceId))
        if (eq) byKey.get(key).deviceMeta = eq
      }
    }

    // 设备表里无维保记录的设备也列出 (维保次数=0)
    for (const eq of equipments) {
      const key = 'id:' + eq.id
      if (!byKey.has(key)) byKey.set(key, { deviceId: eq.id, deviceName: eq.equipmentName, maints: [], deviceMeta: eq })
    }

    // 计算每行指标
    const list = Array.from(byKey.values()).map(g => {
      const meta = g.deviceMeta || {}
      const count = g.maints.length
      // 平均保养周期: 取相邻两条已审核维保记录日期差的中位数 (简化: 平均)
      const dates = g.maints
        .filter(x => x.billStatus === 'CHECKED' && x.maintDate)
        .map(x => new Date(x.maintDate).getTime())
        .filter(t => !isNaN(t))
        .sort((a, b) => a - b)
      let avgCycle = null
      if (dates.length >= 2) {
        const diffs = []
        for (let i = 1; i < dates.length; i++) diffs.push((dates[i] - dates[i - 1]) / 86400000)
        avgCycle = Math.round(diffs.reduce((s, d) => s + d, 0) / diffs.length)
      }
      // 下次到期 = 最早的 nextDueDate
      const nextDues = g.maints.map(x => x.nextDueDate).filter(Boolean)
      const nextDue = nextDues.length ? nextDues.sort((a, b) => String(a).localeCompare(String(b)))[0] : meta.nextMaintDate || null
      const sTag = STATUS_MAP[meta.status] || { tag: 'info', label: meta.status || '未知' }
      return {
        deviceId: g.deviceId,
        equipmentCode: meta.equipmentCode || '-',
        equipmentName: meta.equipmentName || g.deviceName || '-',
        workshop: meta.workshop || '',
        count,
        avgCycle,
        nextDue,
        deviceStatusTag: sTag.tag,
        deviceStatusLabel: sTag.label
      }
    })

    // 排序: 下次到期最近 5 条置顶 (无 nextDue 的排后面), 其他按 count desc
    const sorted = list.sort((a, b) => {
      if (a.nextDue && b.nextDue) {
        const ad = new Date(a.nextDue).getTime(), bd = new Date(b.nextDue).getTime()
        if (ad !== bd) return ad - bd
        return b.count - a.count
      }
      if (a.nextDue) return -1
      if (b.nextDue) return 1
      return b.count - a.count
    })
    rows.value = sorted
  } catch (e) {
    console.warn('[ReportEquipment] 加载失败:', e)
    ElMessage.error((e && (e.msg || e.message)) || '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.section-title { font-size:15px;font-weight:600;color:#333 }
.muted-tip { color:#909399;font-size:12px }
.overdue { color:#f56c6c;font-weight:bold }
</style>
