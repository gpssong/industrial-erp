<template>
  <div class="print-mgmt">
    <div class="svc-banner">
      <span class="svc-icon"><el-icon :size="20"><Printer /></el-icon></span>
      <div class="svc-text"><b>打印管理</b><small>标签打印 · 单据打印模板 · 飞鹅云打印 统一入口</small></div>
    </div>

    <el-tabs v-model="activeTab" class="print-top-tabs">
      <!-- 标签打印：打印中心 + 标签模板（内层子 tab） -->
      <el-tab-pane v-if="canLabelTab" label="标签打印" name="label" lazy>
        <el-tabs v-model="labelSubTab" class="print-sub-tabs">
          <el-tab-pane v-if="canLabel" label="打印" name="print" lazy>
            <LabelCenter embedded @edit-template="onEditTemplate" />
          </el-tab-pane>
          <el-tab-pane v-if="canLabelTpl" label="标签模板" name="template" lazy>
            <LabelTemplate :initial-biz-type="labelBizType" />
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>

      <!-- 打印模板：单据打印模板 -->
      <el-tab-pane v-if="canPrintTpl" label="打印模板" name="template" lazy>
        <PrintTemplate />
      </el-tab-pane>

      <!-- 飞鹅打印：打印机配置 / 打印模板 / 打印日志（内层子 tab） -->
      <el-tab-pane v-if="canFeie" label="飞鹅打印" name="feie" lazy>
        <el-tabs v-model="feieSubTab" class="print-sub-tabs">
          <el-tab-pane label="打印机配置" name="cfg" lazy><FeiePrinterConfig /></el-tab-pane>
          <el-tab-pane label="打印模板" name="tpl" lazy><FeiePrintTemplate /></el-tab-pane>
          <el-tab-pane label="打印日志" name="log" lazy><FeiePrintLog /></el-tab-pane>
        </el-tabs>
      </el-tab-pane>
    </el-tabs>

    <el-empty v-if="!visibleTabs.length" description="无打印管理权限" />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Printer } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import LabelCenter from '@/views/base/LabelCenter.vue'
import LabelTemplate from '@/views/base/LabelTemplate.vue'
import PrintTemplate from '@/views/system/PrintTemplate.vue'
import FeiePrinterConfig from '@/views/system/FeiePrinterConfig.vue'
import FeiePrintTemplate from '@/views/system/FeiePrintTemplate.vue'
import FeiePrintLog from '@/views/system/FeiePrintLog.vue'

const user = useUserStore()
const route = useRoute()
const router = useRouter()

// 复用现有权限点门禁，不新增权限：标签打印 base:label:list / 标签模板 base:label:template /
// 单据打印模板 system:print:list / 飞鹅 system:feie:list（飞鹅真实 API perm）。
const canLabel = computed(() => user.hasPerm('base:label:list'))
const canLabelTpl = computed(() => user.hasPerm('base:label:template'))
const canLabelTab = computed(() => canLabel.value || canLabelTpl.value)
const canPrintTpl = computed(() => user.hasPerm('system:print:list'))
const canFeie = computed(() => user.hasPerm('system:feie:list'))

const visibleTabs = computed(() => {
  const t = []
  if (canLabelTab.value) t.push('label')
  if (canPrintTpl.value) t.push('template')
  if (canFeie.value) t.push('feie')
  return t
})

const activeTab = ref('')
const labelSubTab = ref('print')
const feieSubTab = ref('cfg')
const labelBizType = ref('')

// 深链 ?tab=label|template|feie 定位；不可见则回落第一个有权限的 tab
function pickTab() {
  const q = route.query?.tab
  if (q && visibleTabs.value.includes(q)) { activeTab.value = q; return }
  if (!visibleTabs.value.includes(activeTab.value)) activeTab.value = visibleTabs.value[0] || ''
}
onMounted(pickTab)
watch(() => route.query.tab, pickTab)
watch(visibleTabs, (v) => { if (!v.includes(activeTab.value)) activeTab.value = v[0] || '' })
// 只有标签模板权限（无打印中心权限）时，子 tab 默认落到模板
watch(canLabel, (v) => { if (!v && canLabelTpl.value) labelSubTab.value = 'template' }, { immediate: true })

// 打印中心「编辑这一类模板」：有模板权限切子 tab 并带 bizType；否则走独立路由（守卫处理）
function onEditTemplate(bizType) {
  labelBizType.value = bizType || ''
  if (canLabelTpl.value) labelSubTab.value = 'template'
  else router.push({ path: '/base/label-template', query: { bizType } })
}
</script>

<style scoped lang="scss">
.svc-banner {
  display: flex; align-items: center; gap: 14px;
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 18px 20px; margin-bottom: 16px;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.svc-icon {
  width: 44px; height: 44px; border-radius: 11px; flex: 0 0 auto;
  display: grid; place-items: center; background: #eef5ff; color: var(--primary-color);
}
.svc-text { flex: 1; min-width: 0; }
.svc-text b { display: block; font-size: 15px; font-weight: 730; color: var(--text); }
.svc-text small { display: block; font-size: 11px; color: var(--muted); margin-top: 4px; }
.print-top-tabs { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 8px 16px 16px; }
.print-sub-tabs { margin-top: 4px; }
.print-top-tabs :deep(.el-tabs__header) { margin-bottom: 14px; }
.print-sub-tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
</style>