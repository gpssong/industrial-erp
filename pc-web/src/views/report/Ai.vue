<template>
  <div class="ai-page">
    <div class="card">
      <div class="head">
        <span class="title">🤖 AI 助手</span>
        <span class="hint">自然语言查 ERP 数据; 涉及写操作会先给"待确认"提议, 你点确认后执行</span>
      </div>

      <!-- 对话流 -->
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

      <!-- 输入 -->
      <div class="inputrow">
        <el-input v-model="q" type="textarea" :rows="2" placeholder="如: 查一下透明胶带现在库存多少 / 帮我给客户XX下一张出库单(会先给确认)" @keydown.enter.exact.prevent="send" />
        <el-button type="primary" :loading="busy" @click="send">发送</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { aiApi } from '@/api/sales'
import { salDeliveryApi } from '@/api/sales'
import { ElMessage } from 'element-plus'

const messages = ref([])
const q = ref('')
const busy = ref(false)
const logRef = ref(null)

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
    // 解析 proposedActions (JSON 字符串列表) → {action, params, summary}
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

// 示例提示
if (!messages.value.length) {
  messages.value.push({
    role: 'assistant',
    text: '你好, 我可以查库存、客户、出库单等。试试:「透明胶带现在库存多少」「CKP202610040004 这单什么状态」'
  })
}
</script>

<style scoped>
.ai-page { padding: 12px; }
.card { background: #fff; border-radius: 8px; box-shadow: 0 1px 2px rgba(0,0,0,.05); height: calc(100vh - 120px); display: flex; flex-direction: column; }
.head { padding: 12px 16px; border-bottom: 1px solid #ebeef5; display: flex; gap: 12px; align-items: baseline; }
.head .title { font-size: 16px; font-weight: 600; }
.head .hint { font-size: 12px; color: #909399; }
.log { flex: 1; overflow: auto; padding: 16px; }
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
</style>
