<template>
  <div class="ai-page">
    <div class="card">
      <el-tabs v-model="tab">
        <!-- 对话式助手 -->
        <el-tab-pane label="对话助手" name="chat">
          <div class="head">
            <span class="hint">自然语言查 ERP 数据 / 查文档; 涉及写操作会先给"待确认"提议, 你点确认后执行</span>
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
            <div v-if="busy" class="msg assistant"><div class="bubble"><div class="text typing">AI 正在分析…</div></div></div>
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
import { ref, nextTick, onMounted } from 'vue'
import { aiApi } from '@/api/sales'
import { salDeliveryApi } from '@/api/sales'
import { ElMessage } from 'element-plus'

const tab = ref('chat')
const messages = ref([])
const q = ref('')
const busy = ref(false)
const logRef = ref(null)
// 补货预测
const reKey = ref('')
const reList = ref([])
const reLoading = ref(false)

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

// 确认执行写操作 (白名单: 目前支持 审核出库单; 其它 action 提示暂不支持)
async function execPropose(p) {
  p.executed = '执行中…'
  try {
    switch (p.action) {
      case 'checkSalDelivery': {
        await salDeliveryApi.check(p.params.id)
        p.executed = '✓ 出库单已审核'
        break
      }
      default:
        p.executed = '暂不支持自动执行的写操作: ' + p.action
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

if (!messages.value.length) {
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
.head { padding: 10px 16px; border-bottom: 1px solid #ebeef5; }
.head .hint { font-size: 12px; color: #909399; }
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
