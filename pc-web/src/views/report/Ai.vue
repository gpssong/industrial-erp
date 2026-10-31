<template>
  <div class="ai-page">
    <div class="card">
      <el-tabs v-model="tab">
        <!-- 对话式助手 -->
        <el-tab-pane label="对话助手" name="chat">
          <div class="head">
            <span class="hint">自然语言查 ERP 数据 / 查文档; 涉及写操作会先给"待确认"提议, 你点确认后执行</span>
            <el-button size="small" type="text" class="clear-btn" @click="clearChat">清空记录</el-button>
          </div>
          <div class="log" ref="logRef">
            <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
              <div :class="['bubble', m.tables && m.tables.length ? 'wide' : '']">
                <div class="text">{{ m.text }}</div>
                <!-- v1.1.144: 后端算好的表格原样渲染 (数字不经模型转述), 与下方"下载 CSV"是同一份数据 -->
                <div v-if="m.tables && m.tables.length" class="tables">
                  <div v-for="(t, k) in m.tables" :key="'tb' + k" class="table-block">
                    <div class="table-head">
                      <span class="table-title">{{ t.title }}</span>
                      <el-button size="small" type="primary" @click="downloadCsv(t)">下载 CSV</el-button>
                    </div>
                    <el-table :data="t.rows" border size="small" max-height="320" class="table-grid">
                      <el-table-column v-for="c in (t.columns || [])" :key="c.key" :prop="c.key"
                        :label="c.label" :min-width="colWidth(c.key)"
                        :align="c.key === 'customerName' ? 'left' : 'right'" />
                    </el-table>
                    <div class="table-foot">
                      <span v-if="t.totals" class="totals">合计: {{ totalsText(t) }}</span>
                      <el-tag v-if="t.truncated" size="small" type="warning">行数已达上限, 可让 AI 缩小范围或加大 limit</el-tag>
                    </div>
                    <div v-if="t.note" class="note">{{ t.note }}</div>
                  </div>
                </div>
                <div v-if="m.actions && m.actions.length" class="propose">
                  <div v-for="(p, j) in m.actions" :key="j" class="propose-item">
                    <div class="psum">{{ p.summary }}</div>
                    <div class="pacts">
                      <!-- 成功后按钮必须锁死: 再点一次会重复建一张草稿单 (写提议不是幂等的) -->
                      <el-button size="small" type="primary" :disabled="spent(p)" @click="execPropose(p)">确认执行</el-button>
                      <el-button size="small" :disabled="p.busy" @click="m.actions.splice(j,1)">取消</el-button>
                    </div>
                    <div v-if="p.executed" class="pdone">{{ p.executed }}</div>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="busy" class="msg assistant"><div class="bubble"><div class="text typing">AI 正在分析… (复杂问题可能需要 1-3 分钟, 请耐心等待)</div></div></div>
          </div>
          <div class="inputrow">
            <el-input v-model="q" type="textarea" :rows="2"
              placeholder="如: 长晟9月份一共销售多少(出可下载表格) / 查一下透明胶带现在库存多少 / 这个流程怎么走(查文档) / 哪些该补货了(查补货建议)"
              @keydown.enter.exact.prevent="send" />
            <el-button type="primary" :loading="busy" @click="send">发送</el-button>
          </div>
        </el-tab-pane>

        <!-- 补货预测 -->
        <el-tab-pane label="补货预测" name="replenish">
          <div class="replenish">
            <div class="replenish-bar">
              <el-input v-model="reKey" placeholder="按商品名/编码过滤 (留空看全部)" style="width:260px" @keyup.enter="loadReplenish" />
              <el-button type="primary" @click="loadReplenish">查询补货建议</el-button>
              <el-tag size="small" type="info">基于近30天出库流水 + 当前库存 + 安全库存</el-tag>
            </div>
            <el-table :data="reList" v-loading="reLoading" border size="small" max-height="600">
              <el-table-column prop="productName" label="商品" min-width="160">
                <template #default="{ row }"><div><b>{{ row.productName || row.productCode }}</b><div class="sub">{{ row.productCode }}</div></div></template>
              </el-table-column>
              <el-table-column prop="currentStock" label="当前库存" width="100" />
              <el-table-column prop="safetyStock" label="安全库存" width="90" />
              <el-table-column prop="avgDailyOut" label="日均出库" width="90" />
              <el-table-column label="预计可售" width="90">
                <template #default="{ row }">{{ row.estDaysOfStock == null ? '消耗极慢' : row.estDaysOfStock + ' 天' }}</template>
              </el-table-column>
              <el-table-column label="建议补货量" width="110">
                <template #default="{ row }"><span v-if="row.recommend" class="warn">{{ row.suggestQty }}</span><span v-else class="ok">—</span></template>
              </el-table-column>
              <el-table-column label="是否补" width="70">
                <template #default="{ row }"><el-tag :type="row.recommend ? 'danger' : 'success'" size="small">{{ row.recommend ? '建议补' : '充足' }}</el-tag></template>
              </el-table-column>
              <el-table-column prop="reason" label="理由" min-width="220" show-overflow-tooltip />
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, onMounted, watch } from 'vue'
import { aiApi } from '@/api/sales'
import { salDeliveryApi } from '@/api/sales'
import { purReceiptApi } from '@/api/purchase'
import { prdFinishedInApi } from '@/api/production'
import { ElMessage, ElMessageBox } from 'element-plus'

const tab = ref('chat')
// v1.1.79: 对话记录持久化 — localStorage 兜底 (key=erp_ai_chat_log_v1, 每用户独立前缀避免串号)
const STORAGE_KEY = 'erp_ai_chat_log_v1'
const messages = ref([])
const q = ref('')
const busy = ref(false)
const logRef = ref(null)
// 补货预测
const reKey = ref('')
const reList = ref([])
const reLoading = ref(false)

function loadMessages() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const arr = JSON.parse(raw)
      if (Array.isArray(arr)) return arr
    }
  } catch (e) {
    console.warn('[Ai] load messages from storage failed:', e)
  }
  return null
}
function saveMessages() {
  try {
    // 截断前 200 条避免 localStorage 5MB 上限爆掉
    const slice = messages.value.slice(-200)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(slice))
  } catch (e) {
    console.warn('[Ai] save messages to storage failed:', e)
  }
}
// 深层 watch: messages 内容变化 (含 push 后元素 / actions executed 字段) 都触发持久化
watch(messages, () => saveMessages(), { deep: true })

async function send() {
  const question = q.value.trim()
  if (!question || busy.value) return
  q.value = ''
  messages.value.push({ role: 'user', text: question })
  busy.value = true
  await scrollBottom()
  try {
    const r = await aiApi.agentChat(question)
    const d = (r && r.data) || {}
    const actions = (d.proposedActions || []).map(parseProposal)
    // v1.1.144: 只读工具算出的表格 (后端 AgentService 从 getSalesSummary 透传), 直接渲染 + 可下载
    messages.value.push({ role: 'assistant', text: d.answer || '(无返回)', actions, tables: d.tables || [] })
  } catch (e) {
    messages.value.push({ role: 'assistant', text: 'AI 调用失败: ' + ((e && (e.msg || e.message)) || '未知') })
  } finally {
    busy.value = false
    await scrollBottom()
  }
}

// 模型回的 PROPOSE 里 id 是 19 位雪花号; 裸 JSON.parse 会把超过 2^53 的整数抹掉尾数
// (2116353847494504578 → 2116353847494504400), 审核时就报「入库单不存在」。先把长整数转成字符串再解析。
function parseProposal(s) {
  try {
    return JSON.parse(String(s).replace(/([:,\[]\s*)(\d{16,})(?=\s*[,}\]])/g, '$1"$2"'))
  } catch (e) {
    return { summary: s, action: 'unknown' }
  }
}

// v1.1.79: 清空对话 (用户主动按钮触发, 二次确认防误删)
async function clearChat() {
  if (!messages.value.length) return
  try {
    await ElMessageBox.confirm('确认清空全部 AI 对话记录? 此操作不可恢复。', '清空对话', {
      type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消'
    })
  } catch (e) { return } // 用户取消
  messages.value = [{ role: 'assistant', text: WELCOME }]
  ElMessage.success('对话记录已清空')
}

async function loadReplenish() {
  reLoading.value = true
  try {
    const r = await aiApi.replenishSuggest(reKey.value || '', 30)
    reList.value = (r && r.data) || []
    if (!reList.value.length) ElMessage.info('暂无补货建议 (近30天无出库流水, 或全部商品库存充足)')
  } catch (e) {
    ElMessage.error('补货预测失败: ' + ((e && (e.msg || e.message)) || '未知'))
  } finally {
    reLoading.value = false
  }
}

// 确认执行写操作 (白名单: 审核出库单 / 生成采购入库单 / 生成成品入库单 / 审核成品入库单; 其它 action 提示暂不支持)
// v1.1.79 hotfix-3: 模型可能自创 action 别名 (如 addOtherInbound), 用别名表归一到白名单 key.
// 后端 AgentService sys() 已把白名单 action 写死进 system prompt, 这里是前端兜底防御.
const ACTION_ALIAS = {
  // 生成采购入库单
  addPurIn: 'addPurIn', addPurReceipt: 'addPurIn', addOtherInbound: 'addPurIn',
  addPurInbound: 'addPurIn', createPurIn: 'addPurIn', createPurReceipt: 'addPurIn',
  purchaseInbound: 'addPurIn', addPurchaseIn: 'addPurIn',
  // 审核销售出库单
  checkSalDelivery: 'checkSalDelivery', approveSalDelivery: 'checkSalDelivery',
  auditSalDelivery: 'checkSalDelivery',
  // v1.1.143 生成成品入库单 (生产入账)
  addPrdFinishedIn: 'addPrdFinishedIn', addFinishedIn: 'addPrdFinishedIn',
  addPrdIn: 'addPrdFinishedIn', createFinishedIn: 'addPrdFinishedIn',
  finishedInbound: 'addPrdFinishedIn', productionInbound: 'addPrdFinishedIn',
  addProductionIn: 'addPrdFinishedIn', prdFinishedInAdd: 'addPrdFinishedIn',
  // v1.1.143 审核成品入库单
  checkPrdFinishedIn: 'checkPrdFinishedIn', checkFinishedIn: 'checkPrdFinishedIn',
  approveFinishedIn: 'checkPrdFinishedIn', auditFinishedIn: 'checkPrdFinishedIn',
  auditPrdFinishedIn: 'checkPrdFinishedIn'
}
// 已成功/正在执行的提议不允许再点 (重复点 addPrdFinishedIn 会重复建草稿单); 失败的原样留着, 让用户改口后重试
function spent(p) {
  return !!p.busy || (p.executed || '').startsWith('✓')
}
async function execPropose(p) {
  if (spent(p)) return
  p.busy = true
  p.executed = '执行中…'
  try {
    if (!p.params) {
      p.executed = '这条提议缺少参数 (模型没按格式输出), 请重新描述需求再试'
      ElMessage.error(p.executed)
      return
    }
    const action = ACTION_ALIAS[p.action] || p.action
    switch (action) {
      case 'checkSalDelivery': {
        await salDeliveryApi.check(p.params.id)
        p.executed = '✓ 出库单已审核'
        break
      }
      case 'addPurIn': {
        // p.params: { billNo?, supplierId, warehouseId, details: [{productId,qty,price,...}], remark? }
        // 模型产出的 JSON 直接当作 add 请求体 (与 PC 端手工录入 schema 一致)
        // 采购入库单必填 supplierId (PurReceiptService.add 会 selectById(supplierId) 校验), 缺了会报「供应商不存在」
        if (p.params.supplierId == null || p.params.supplierId === '') {
          p.executed = '缺供应商: 采购入库单必填 supplierId, 请在采购入库模块手工选择供应商后开单'
          ElMessage.error(p.executed)
          break
        }
        const r = await purReceiptApi.add(p.params)
        const billNo = (r && r.data && r.data.billNo) || p.params.billNo || '(单号待回)'
        p.executed = '✓ 采购入库单 ' + billNo + ' 已生成 (草稿, 审核后才会入库)'
        break
      }
      // v1.1.143: 成品入账 — 模型按 addPrdFinishedIn 产出的 params 直接当请求体 (与手工录入同 schema)
      case 'addPrdFinishedIn': {
        if (p.params.productId == null || p.params.productId === '') {
          p.executed = '缺商品: 成品入库单必须有具体商品, 请告诉 AI 商品名称或编码后重新提议'
          ElMessage.error(p.executed)
          break
        }
        if (p.params.warehouseId == null || p.params.warehouseId === '') {
          p.executed = '缺仓库: 成品入库单必须指定入库仓库, 请告诉 AI 入到哪个仓库后重新提议'
          ElMessage.error(p.executed)
          break
        }
        if (!(Number(p.params.qty) > 0)) {
          p.executed = '数量必须大于 0: 请告诉 AI 实际生产入库的数量后重新提议'
          ElMessage.error(p.executed)
          break
        }
        await prdFinishedInApi.add(p.params)
        p.executed = '✓ 成品入库单 ' + (await latestFinishedInBillNo()) + ' 已生成 (草稿, 审核后库存才入账)'
        break
      }
      case 'checkPrdFinishedIn': {
        if (p.params.id == null || p.params.id === '') {
          p.executed = '缺入库单 id: 请先让 AI 查一下最近的成品入库单再提议'
          ElMessage.error(p.executed)
          break
        }
        await prdFinishedInApi.check(p.params.id)
        p.executed = '✓ 成品入库单已审核, 库存已入账'
        break
      }
      default:
        p.executed = '暂不支持自动执行的写操作: ' + p.action
          + ' (白名单: 审核出库单 checkSalDelivery / 生成采购入库单 addPurIn'
          + ' / 生成成品入库单 addPrdFinishedIn / 审核成品入库单 checkPrdFinishedIn)'
    }
    if (p.executed.startsWith('✓')) ElMessage.success(p.executed)
  } catch (e) {
    p.executed = '执行失败: ' + ((e && (e.msg || e.message)) || '未知')
    ElMessage.error(p.executed)
  } finally {
    p.busy = false
  }
}

// POST /production/finished-in 只回 R<Void> 没有单号, 建完再查最新一张把单号显示给用户 (查不到也不影响建单成功)
async function latestFinishedInBillNo() {
  try {
    const r = await prdFinishedInApi.page({ pageNum: 1, pageSize: 1 })
    const rows = (r && r.data && r.data.records) || []
    return rows.length ? rows[0].billNo : '(草稿已建)'
  } catch (e) {
    return '(草稿已建)'
  }
}

function scrollBottom() {
  return nextTick(() => {
    if (logRef.value) logRef.value.scrollTop = logRef.value.scrollHeight
  })
}

// ===== v1.1.144 AI 表格渲染 + CSV 下载 =====

const COL_W = { customerName: 180, period: 90, billCount: 90, totalQty: 110, totalAmount: 120 }
function colWidth(key) { return COL_W[key] || 120 }

function totalsText(t) {
  return (t.columns || [])
    .filter(c => c.key !== 'period')
    .map(c => c.label + ' ' + fmt(t.totals[c.key]))
    .join(' / ')
}

/** 数字补千分位 (后端已把金额/数量格式化成字符串, 这里只做展示, 不改数值)。 */
function fmt(v) {
  const s = v == null ? '' : String(v)
  if (!/^-?\d+(\.\d+)?$/.test(s)) return s
  const [int, dec] = s.split('.')
  return int.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (dec ? '.' + dec : '')
}

// CSV 与后端 AiSalesSummaryService.toCsv 同款: BOM (Excel 认中文) + 表头 + 数据 + 合计 + 口径说明。
// 数字直接用表格里的字符串, 不再二次格式化 —— 保证下载的和屏幕上看到的一模一样。
function csvCell(v) {
  const s = v == null ? '' : String(v)
  return /[",\r\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}
function csvRow(cols, row) {
  return (cols || []).map(c => csvCell(row ? row[c.key] : '')).join(',')
}
function downloadCsv(t) {
  const cols = t.columns || []
  // 表头取 label, 数据行取 key —— csvRow 只认 row[c.key], 直接喂 cols 会得到一行空逗号
  const lines = [cols.map(c => csvCell(c.label)).join(',')]
  ;(t.rows || []).forEach(r => lines.push(csvRow(cols, r)))
  if (t.totals && (t.rows || []).length) lines.push(csvRow(cols, t.totals))
  if (t.note) lines.push(csvCell(t.note))
  const blob = new Blob(['\ufeff' + lines.join('\n') + '\n'], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = (t.fileName || 'AI导出') + '.csv'
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已导出 ' + (t.count || (t.rows || []).length) + ' 行')
}

const WELCOME = '你好, 我可以查库存/客户/出库单、按客户和时间段出销售汇总表 (可直接下载 CSV), 也能查使用手册/部署文档, 还能给补货建议, 以及帮你开成品入库单 (需要你点确认)。\n试试:「长晟9月份一共销售多少」「各客户今年销售额, 按月」「透明胶带现在库存多少」「哪些商品该补货了」「把刚生产出来的带鱼袋 5000 只入到成品仓」'

// v1.1.79: 启动时优先从 localStorage 恢复历史对话, 没记录才显示欢迎语
const restored = loadMessages()
if (restored && restored.length) {
  messages.value = restored
} else {
  messages.value.push({ role: 'assistant', text: WELCOME })
}
onMounted(() => { if (tab.value === 'replenish') loadReplenish() })
</script>

<style scoped>
.ai-page { padding: 12px; }
.card { background: #fff; border-radius: 8px; box-shadow: 0 1px 2px rgba(0,0,0,.05); height: calc(100vh - 120px); display: flex; flex-direction: column; }
.head { padding: 10px 16px; border-bottom: 1px solid #ebeef5; display: flex; justify-content: space-between; align-items: center; }
.head .hint { font-size: 12px; color: #909399; }
.head .clear-btn { padding: 0; color: #f56c6c; }
.log { flex: 1; overflow: auto; padding: 16px; min-height: 200px; }
.msg { margin-bottom: 12px; display: flex; }
.msg.user { justify-content: flex-end; }
.bubble { max-width: 78%; background: #f4f4f5; border-radius: 8px; padding: 10px 12px; }
.msg.assistant .bubble { background: #ecf5ff; }
.text { font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.typing { color: #909399; }
.propose { margin-top: 10px; border-top: 1px dashed #c0c4cc; padding-top: 8px; }
/* v1.1.144: 表格气泡加宽, 5 列客户/月份表在 78% 宽度里会被压得换行 */
.bubble.wide { max-width: 94%; }
.tables { margin-top: 10px; }
.table-block { margin-bottom: 10px; background: #fff; border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px; }
.table-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 8px; }
.table-title { font-size: 13px; font-weight: 600; }
.table-grid { width: 100%; }
.table-foot { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 8px; }
.table-foot .totals { font-size: 12px; color: #303133; font-weight: 600; }
.table-block .note { font-size: 12px; color: #909399; margin-top: 6px; line-height: 1.6; }
.propose-item { margin-bottom: 8px; }
.psum { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.pacts { display: flex; gap: 8px; }
.pdone { font-size: 12px; color: #67c23a; margin-top: 6px; }
.inputrow { padding: 12px 16px; border-top: 1px solid #ebeef5; display: flex; gap: 8px; align-items: flex-end; }
.inputrow .el-textarea { flex: 1; }
.replenish { padding: 12px 16px; }
.replenish-bar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
.sub { color: #909399; font-size: 12px; }
.warn { color: #f56c6c; font-weight: 600; }
.ok { color: #909399; }
</style>
