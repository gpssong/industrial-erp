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
              <div class="bubble">
                <div class="text">{{ m.text }}</div>
                <div v-if="m.actions && m.actions.length" class="propose">
                  <div v-for="(p, j) in m.actions" :key="j" class="propose-item">
                    <div class="psum">{{ p.summary }}</div>
                    <div class="pacts">
                      <el-button size="small" type="primary" @click="execPropose(p)">确认执行</el-button>
                      <el-button size="small" @click="m.actions.splice(j,1)">取消</el-button>
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
              placeholder="如: 查一下透明胶带现在库存多少 / 这个流程怎么走(查文档) / 哪些该补货了(查补货建议)"
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
    const actions = (d.proposedActions || []).map(s => {
      try { return JSON.parse(s) } catch (e) { return { summary: s, action: 'unknown' } }
    })
    messages.value.push({ role: 'assistant', text: d.answer || '(无返回)', actions })
  } catch (e) {
    messages.value.push({ role: 'assistant', text: 'AI 调用失败: ' + ((e && (e.msg || e.message)) || '未知') })
  } finally {
    busy.value = false
    await scrollBottom()
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
  messages.value = [{
    role: 'assistant',
    text: '你好, 我可以查库存/客户/出库单, 也能查使用手册/部署文档, 还能给补货建议。\n试试:「透明胶带现在库存多少」「销售出库怎么审核」「哪些商品该补货了」'
  }]
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

// 确认执行写操作 (白名单: 目前支持 审核出库单 / 生成采购入库; 其它 action 提示暂不支持)
// v1.1.79 hotfix-3: 模型可能自创 action 别名 (如 addOtherInbound), 用别名表归一到白名单 key.
// 后端 AgentService sys() 已把白名单 action 写死进 system prompt, 这里是前端兜底防御.
const ACTION_ALIAS = {
  // 生成采购入库单
  addPurIn: 'addPurIn', addPurReceipt: 'addPurIn', addOtherInbound: 'addPurIn',
  addPurInbound: 'addPurIn', createPurIn: 'addPurIn', createPurReceipt: 'addPurIn',
  purchaseInbound: 'addPurIn', addPurchaseIn: 'addPurIn',
  // 审核销售出库单
  checkSalDelivery: 'checkSalDelivery', approveSalDelivery: 'checkSalDelivery',
  auditSalDelivery: 'checkSalDelivery'
}
async function execPropose(p) {
  p.executed = '执行中…'
  try {
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
      default:
        p.executed = '暂不支持自动执行的写操作: ' + p.action + ' (白名单: 审核出库单 checkSalDelivery / 生成采购入库单 addPurIn)'
    }
    ElMessage.success(p.executed.startsWith('✓') ? p.executed : '已完成')
  } catch (e) {
    p.executed = '执行失败: ' + ((e && (e.msg || e.message)) || '未知')
    ElMessage.error(p.executed)
  }
}

function scrollBottom() {
  return nextTick(() => {
    if (logRef.value) logRef.value.scrollTop = logRef.value.scrollHeight
  })
}

// v1.1.79: 启动时优先从 localStorage 恢复历史对话, 没记录才显示欢迎语
const restored = loadMessages()
if (restored && restored.length) {
  messages.value = restored
} else {
  messages.value.push({
    role: 'assistant',
    text: '你好, 我可以查库存/客户/出库单, 也能查使用手册/部署文档, 还能给补货建议。\n试试:「透明胶带现在库存多少」「销售出库怎么审核」「哪些商品该补货了」'
  })
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
