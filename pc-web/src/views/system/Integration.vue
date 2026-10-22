<template>
  <div>
    <!-- 总说明 -->
    <div class="page-card" style="margin-bottom:16px">
      <div class="section-title">🔌 对接方式 (Hermes / Dify / AI 网关)</div>
      <p class="muted-tip">
        本页集中展示 ERP 对外的 4 类对接通道: LLM 大模型网关 / 语音识别 (ASR) / MCP (Hermes 自动建档) / Dify (外部工作流触发)。
        LLM/ASR 的 provider 与 key 由 backend env (<code>ERP_AI_*</code> / <code>ERP_AI_ASR_*</code>) 决定，
        本页只<b>展示脱敏状态</b>并记录「provider 顺序意图」(实际生效需重建 jar 注入新 env)。
      </p>
    </div>

    <!-- 卡片 1: 模型接口配置 (v1.1.117 — 主备 2 模型 + 测试连接 + 3 能力开关) -->
    <div class="page-card" style="margin-bottom:16px" v-loading="loading.ai">
      <div class="card-head">
        <div>
          <div class="section-title">模型接口配置</div>
          <div class="muted-tip">API 密钥加密保存，AI 只能访问授权业务数据</div>
        </div>
        <div>
          <el-button size="small" :loading="testing.primary" @click="onTest('primary')">测试主模型</el-button>
          <el-button size="small" :loading="testing.backup" @click="onTest('backup')" style="margin-left:8px">测试备用模型</el-button>
        </div>
      </div>

      <!-- 主模型 + 备用模型 两栏 -->
      <el-form label-width="86px" label-position="left">
        <div v-for="slot in SLOT_LIST" :key="slot.key" class="slot-block">
          <div class="slot-head">
            <el-tag size="small" :type="slot.key === 'primary' ? 'warning' : 'info'" effect="plain">
              {{ slot.key === 'primary' ? '主模型' : '备用模型' }}
            </el-tag>
            <span class="muted-tip">{{ slot.hint }}</span>
            <el-switch v-model="llmForm.slots[slot.key].enabled" active-text="启用" style="margin-left:auto" />
          </div>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="服务供应商">
                <el-select v-model="llmForm.slots[slot.key].provider" style="width:100%" @change="(v) => onProviderChange(slot.key, v)">
                  <el-option label="OpenAI兼容接口" value="openai-compatible" />
                  <el-option label="Agnes (阿奇斯)" value="agnes" />
                  <el-option label="MiniMax" value="minimax" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="模型名称">
                <el-input v-model="llmForm.slots[slot.key].model" placeholder="如 enterprise-assistant-v1" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="API地址">
                <el-input v-model="llmForm.slots[slot.key].baseUrl" placeholder="https://api.example.com/v1" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="API Key">
                <el-input v-model="llmForm.slots[slot.key].key" type="password" show-password placeholder="留空 = 沿用后端 env" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="请求超时">
                <el-select v-model="llmForm.slots[slot.key].timeoutMs" style="width:100%">
                  <el-option :value="30000" label="30秒" />
                  <el-option :value="60000" label="60秒" />
                  <el-option :value="120000" label="120秒" />
                  <el-option :value="300000" label="300秒" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="每日调用上限">
                <el-input v-model.number="llmForm.dailyLimit" type="number" min="0" placeholder="1000" />
              </el-form-item>
            </el-col>
          </el-row>
        </div>
      </el-form>

      <!-- 3 能力开关 (整行卡片 + 右侧勾选, 写操作默认关) -->
      <div class="caps">
        <label v-for="cap in CAP_LIST" :key="cap.key" class="cap-row" :class="{ danger: cap.key === 'allowWrite' }">
          <div class="cap-text">
            <b>{{ cap.title }}</b>
            <small>{{ cap.desc }}</small>
          </div>
          <el-checkbox v-model="llmForm.capabilities[cap.key]" />
        </label>
      </div>

      <el-alert v-if="testMsg" :type="testOk ? 'success' : 'error'" :closable="false" show-icon style="margin-top:12px">
        {{ testMsg }}
      </el-alert>

      <div class="card-foot">
        <el-button type="primary" size="small" :loading="saving" @click="onSave">保存并即时生效</el-button>
        <el-button size="small" @click="onResetEnv">还原 (纯 env)</el-button>
        <span class="muted-tip" style="margin-left:12px">
          生效顺序: <code>{{ liveOrderStr }}</code> · 当前命中: <code>{{ llm.activeProvider || '—' }}</code>
        </span>
      </div>
      <el-alert v-if="!llm.dbOverrideActive" type="info" :closable="false" show-icon style="margin-top:10px">
        当前走 backend env (<code>ERP_AI_*</code>)。填 API key 并点「保存并即时生效」即写入
        <code>sys_config.AI_INTEGRATION</code> 并立即切换 (无需重建 jar); 留空的 key 自动回落 env。
      </el-alert>
      <el-alert type="info" :closable="false" show-icon style="margin-top:10px">
        主模型失败 (5xx / 超时 / 429) 时自动切换到备用模型; 主模型 key 留空则只走备用。
      </el-alert>
    </div>

    <!-- 卡片 2: 语音识别 ASR -->
    <div class="page-card" style="margin-bottom:16px" v-loading="loading.asr">
      <div class="card-head">
        <div class="section-title">🎤 语音识别 (ASR) <el-tag :type="asr.enabled ? 'success' : 'info'" size="small" style="margin-left:8px">{{ asr.enabled ? '已配置' : '未配置' }}</el-tag></div>
      </div>
      <el-descriptions :column="3" border size="small" style="max-width:900px">
        <el-descriptions-item label="provider">dashscope</el-descriptions-item>
        <el-descriptions-item label="model">{{ asr.model }}</el-descriptions-item>
        <el-descriptions-item label="base_url">{{ asr.baseUrl }}</el-descriptions-item>
        <el-descriptions-item label="key">{{ asr.keyMasked }}</el-descriptions-item>
        <el-descriptions-item label="timeout">{{ asr.timeoutMs }} ms</el-descriptions-item>
        <el-descriptions-item label="用途">App 端 AI 助手语音输入后端兜底</el-descriptions-item>
      </el-descriptions>
      <div class="asr-key-row">
        <el-input v-model="integration.asr.key" placeholder="ASR API key (留空 = 回落 env ERP_AI_ASR_KEY)"
          type="password" show-password size="small" style="width:360px" />
      </div>
      <p class="muted-tip" style="margin-top:8px">
        未配置 key 时语音识别快速失败, 不影响其他功能。填 key 后随 LLM 卡片「保存并即时生效」一起写入 <code>AI_INTEGRATION</code> 即时生效。
      </p>
    </div>

    <!-- 卡片 3: MCP (Hermes) -->
    <div class="page-card" style="margin-bottom:16px" v-loading="loading.mcp">
      <div class="card-head">
        <div class="section-title">🔌 MCP (Hermes 自动建档) <el-tag :type="mcp.bot?.exists ? 'success' : 'warning'" size="small" style="margin-left:8px">{{ mcp.bot?.exists ? `bot ${mcp.bot.username}` : 'bot 未建' }}</el-tag></div>
        <div>
          <el-button size="small" type="primary" :loading="mcpTesting" @click="testMcp">测试 tools/list</el-button>
          <el-button size="small" @click="loadMcp" :loading="loading.mcp">刷新</el-button>
        </div>
      </div>
      <el-descriptions :column="2" border size="small" style="max-width:900px">
        <el-descriptions-item label="端点">{{ mcp.endpoint }}</el-descriptions-item>
        <el-descriptions-item label="协议">{{ mcp.protocolVersion }}</el-descriptions-item>
        <el-descriptions-item label="工具白名单">
          <template #label>
            <span>工具白名单 <el-tooltip content="只开放建单/查单, check/delete 一律不暴露" placement="top"><el-icon><QuestionFilled /></el-icon></el-tooltip></span>
          </template>
          <div v-for="t in mcp.tools" :key="t.name" class="tool-row">
            <code class="tool-name">{{ t.name }}</code>
            <span class="muted-tip">{{ t.description }}</span>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="bot 账号">
          {{ mcp.bot?.username }} · {{ mcp.bot?.exists ? (mcp.bot?.status === 1 ? '启用' : '停用') : '未创建' }}
        </el-descriptions-item>
      </el-descriptions>
      <p class="muted-tip" style="margin-top:8px">{{ mcp.note }}</p>
      <el-alert v-if="mcpTestMsg" :type="mcpTestOk ? 'success' : 'error'" :closable="false" show-icon style="margin-top:8px">
        MCP 探活: {{ mcpTestMsg }}
      </el-alert>
    </div>

    <!-- 卡片 4: Dify -->
    <div class="page-card" v-loading="loading.dify">
      <div class="card-head">
        <div class="section-title">⚡ Dify (外部工作流触发) <el-tag :type="dify.botExists ? 'success' : 'warning'" size="small" style="margin-left:8px">{{ dify.botExists ? `bot ${dify.botUsername}` : 'bot 未建' }}</el-tag></div>
        <el-button size="small" @click="copyDifyParams">复制对接参数</el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px">
        Dify 是外部编排平台, 通过 HTTP 调 ERP <code>/api/workflow/*</code> 自动生成维保/安全检查/应急预案草稿。
        ERP 侧<b>无 Dify client 代码</b> (纯 inbound), 本页仅汇总对接参数供复制到 Dify HTTP 节点。
      </el-alert>
      <el-descriptions :column="2" border size="small" style="max-width:900px">
        <el-descriptions-item label="方向">{{ dify.direction }}</el-descriptions-item>
        <el-descriptions-item label="bot 账号">{{ dify.botUsername }} · {{ dify.botExists ? (dify.botStatus === 1 ? '启用' : '停用') : '未创建' }}</el-descriptions-item>
        <el-descriptions-item label="触发端点" :span="2">
          <div v-for="ep in dify.endpoints" :key="ep"><code class="tool-name">{{ ep }}</code></div>
        </el-descriptions-item>
        <el-descriptions-item label="鉴权方式" :span="2">{{ dify.auth }}</el-descriptions-item>
        <el-descriptions-item label="参考文档" :span="2"><code class="tool-name">{{ dify.docs }}</code></el-descriptions-item>
      </el-descriptions>
      <p class="muted-tip" style="margin-top:8px">{{ dify.note }}</p>
    </div>

    <!-- 审计提示 -->
    <div class="page-card" style="margin-top:16px">
      <div class="section-title">📋 说明</div>
      <p class="muted-tip">
        provider 顺序 / key / model 覆盖写 <code>sys_config.AI_INTEGRATION</code> (门禁 <code>system:config:edit</code>)，保存后即时生效、无需重建 jar; 留空字段回落 env。
        LLM/ASR/MCP/Dify 的状态均为脱敏展示, key 一律打码 (首 4 + **** + 末 4), 不回传明文。
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { integrationApi } from '@/api/system'

const loading = reactive({ ai: false, asr: false, mcp: false, dify: false })
const saving = ref(false)
const testing = reactive({ primary: false, backup: false })
const testMsg = ref('')
const testOk = ref(false)
const mcpTesting = ref(false)
const mcpTestMsg = ref('')
const mcpTestOk = ref(false)

/** LLM 网关状态 (脱敏) */
const llm = ref({ enabled: false, providers: [], providerOrder: '', activeProvider: '—', liveProviderOrder: [], dbOverrideActive: false })
/** v1.1.117: 模型接口配置表单 (主备 2 模型). key 留空 = 保存时不覆盖, 回落 env. */
const llmForm = reactive({
  slots: {
    primary: { enabled: true, provider: 'openai-compatible', model: '', baseUrl: '', key: '', timeoutMs: 60000 },
    backup:  { enabled: false, provider: 'openai-compatible', model: '', baseUrl: '', key: '', timeoutMs: 60000 }
  },
  dailyLimit: 1000,
  capabilities: { business: true, quality: true, allowWrite: false }
})
/** 主/备两槽展示定义. */
const SLOT_LIST = [
  { key: 'primary', hint: '优先调用, 失败自动切备用' },
  { key: 'backup', hint: '主模型 5xx / 超时 / 429 时接管' }
]
/** 3 能力开关定义 (label + 说明, 顺序即展示顺序). */
const CAP_LIST = [
  { key: 'business', title: '经营数据问答', desc: '销售、采购、库存和应收应付分析' },
  { key: 'quality', title: '质量与XK辅助', desc: '检验记录总结和到期风险提醒' },
  { key: 'allowWrite', title: '允许执行写操作', desc: '默认关闭, 防止 AI 直接修改业务数据' }
]
/** provider 预设 (下拉切换时回填默认 model/baseUrl). */
const PROVIDER_PRESETS = {
  'openai-compatible': { model: '', baseUrl: '' },
  agnes: { model: 'agnes-2.5-flash', baseUrl: 'https://apihub.agnes-ai.com/v1' },
  minimax: { model: 'MiniMax-M3', baseUrl: 'https://api.minimaxi.com/v1' }
}
/** v1.1.80: ASR key (卡片 2 独立填, 随保存一起写 AI_INTEGRATION). */
const integration = reactive({ asr: { key: '' } })
/** ASR 状态 */
const asr = ref({ enabled: false, model: '—', baseUrl: '', keyMasked: '(未配置)', timeoutMs: 0 })
/** MCP 状态 */
const mcp = ref({ endpoint: '', protocolVersion: '', tools: [], bot: {} })
/** Dify 状态 */
const dify = ref({ clientExists: false, direction: '', botUsername: '', botExists: false, endpoints: [], auth: '', docs: '', note: '' })

/** 当前即时生效的 provider 顺序 (后端 DB 覆盖后回传的 liveProviderOrder). */
const liveOrderStr = computed(() => {
  const arr = llm.value.liveProviderOrder || []
  return arr.length ? arr.join(', ') : '(未配置 LLM)'
})

async function loadAi() {
  loading.ai = true; loading.asr = true
  try {
    const r = await integrationApi.getAi()
    const d = r?.data || {}
    llm.value = d.llm || { enabled: false, providers: [], providerOrder: '' }
    asr.value = d.asr || { enabled: false, model: '—' }
    // 回填主/备两槽: 后端 slot0 = 主, slot1 = 备 (key 一律不打回, 留空 = 回落 env)
    const provs = llm.value.providers || []
    const fill = (slotKey, idx) => {
      const p = provs.find((x) => x.slot === idx) || {}
      const slot = llmForm.slots[slotKey]
      slot.provider = PROVIDER_PRESETS[p.name] ? p.name : 'openai-compatible'
      slot.model = p.model || ''
      slot.baseUrl = p.baseUrl || ''
      slot.timeoutMs = p.timeoutMs || 60000
      slot.key = ''
      slot.enabled = !!p.configured
    }
    fill('primary', 0)
    fill('backup', 1)
    // v1.1.117: 能力开关 + 每日上限回显 (缺省 business/quality=true, allowWrite=false)
    const caps = d.llm?.capabilities || {}
    llmForm.capabilities.business = caps.business !== false
    llmForm.capabilities.quality = caps.quality !== false
    llmForm.capabilities.allowWrite = caps.allowWrite === true
    llmForm.dailyLimit = caps.dailyLimit || 1000
    integration.asr.key = ''
  } catch (e) {
    console.warn('[Integration] 加载 AI 状态失败:', e)
  } finally {
    loading.ai = false; loading.asr = false
  }
}

/** 切换供应商预设 → 回填默认 model/baseUrl. */
function onProviderChange(slotKey, v) {
  const p = PROVIDER_PRESETS[v]
  if (!p) return
  llmForm.slots[slotKey].model = p.model
  llmForm.slots[slotKey].baseUrl = p.baseUrl
}

/** 槽位 key → 后端 slot 名 (后端只认 agnes/minimax 两个 slot). */
function slotProviderName(slot) {
  if (slot.provider === 'minimax') return 'minimax'
  if (slot.provider === 'agnes') return 'agnes'
  // openai-compatible: 落到 agnes 槽 (槽位只决定 failover 顺序, baseUrl/model/key 全可覆盖)
  return 'agnes'
}

/** v1.1.117: 测试连接 — 用指定槽当前值探活, 不落库. */
async function onTest(which) {
  const slot = llmForm.slots[which]
  if (!slot.key.trim() && !slot.baseUrl.trim()) {
    testOk.value = false
    testMsg.value = `请先填写${which === 'primary' ? '主' : '备用'}模型的 API 地址与 API Key`
    return
  }
  testing[which] = true
  testMsg.value = ''
  try {
    const resp = await integrationApi.testLlm({
      name: `${slotProviderName(slot)}:${which}`,
      key: slot.key.trim(),
      model: slot.model.trim(),
      baseUrl: slot.baseUrl.trim(),
      timeoutMs: slot.timeoutMs
    })
    const d = resp?.data || {}
    testOk.value = !!d.ok
    testMsg.value = d.ok
      ? `${which === 'primary' ? '主' : '备用'}模型连接成功 · 延迟 ${d.latencyMs} ms`
      : `${which === 'primary' ? '主' : '备用'}模型连接失败: ${d.errMsg || '未知错误'}`
  } catch (e) {
    testOk.value = false
    testMsg.value = `测试请求失败: ${(e && (e.msg || e.message)) || '网络错误'}`
  } finally {
    testing[which] = false
  }
}

/** v1.1.117: 保存 — 组装 AI_INTEGRATION JSON (主 slot0 + 备 slot1 + 能力开关 + 上限), 即时生效. */
async function onSave() {
  saving.value = true
  try {
    const buildSlot = (slotKey) => {
      const s = llmForm.slots[slotKey]
      const o = {}
      // key 留空 = 不覆盖 (回落 env); 其余字段总是写
      if (s.key.trim()) o.key = s.key.trim()
      o.model = s.model.trim()
      o.baseUrl = s.baseUrl.trim()
      o.timeoutMs = String(s.timeoutMs || 60000)
      return o
    }
    const p = llmForm.slots.primary
    const b = llmForm.slots.backup
    const order = []
    if (p.enabled) order.push(slotProviderName(p))
    if (b.enabled) order.push(slotProviderName(b))
    if (!order.length) {
      ElMessage.warning('主模型与备用模型不能同时停用')
      return
    }

    const payload = {
      llm: {
        providerOrder: [...new Set(order)].join(','),
        slot0: buildSlot('primary'),
        slot1: buildSlot('backup'),
        capabilities: {
          business: !!llmForm.capabilities.business,
          quality: !!llmForm.capabilities.quality,
          allowWrite: !!llmForm.capabilities.allowWrite
        },
        dailyLimit: String(llmForm.dailyLimit || 0)
      },
      asr: {}
    }
    // ASR key (卡片 2) 若填了, 一起写
    if (integration.asr.key && integration.asr.key.trim()) {
      payload.asr.key = integration.asr.key.trim()
    }
    const json = JSON.stringify(payload)
    await integrationApi.applyIntegration(json)
    await loadAi()   // 重新拉状态 (DB 已生效)
    ElMessage.success(`已即时生效 · 顺序 ${liveOrderStr.value} · 当前命中: ${llm.value.activeProvider || '—'}`)
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 还原 = 清除 DB 覆盖 (回落纯 env). */
async function onResetEnv() {
  await ElMessageBox.confirm('清除 sys_config.AI_INTEGRATION 覆盖, 回落纯 env 配置? 即时生效。', '还原', { type: 'warning' })
    .catch(() => { throw new Error('cancel') })
  saving.value = true
  try {
    await integrationApi.applyIntegration('')
    await loadAi()
    ElMessage.success('已还原为纯 env 配置')
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '还原失败')
  } finally {
    saving.value = false
  }
}

async function loadMcp() {
  loading.mcp = true
  try {
    const r = await integrationApi.getMcp()
    mcp.value = r?.data || { tools: [], bot: {} }
  } catch (e) {
    console.warn('[Integration] 加载 MCP 状态失败:', e)
  } finally {
    loading.mcp = false
  }
}

async function loadDify() {
  loading.dify = true
  try {
    const r = await integrationApi.getDify()
    dify.value = r?.data || { botUsername: 'dify_bot', botExists: false, endpoints: [] }
  } catch (e) {
    console.warn('[Integration] 加载 Dify 状态失败:', e)
  } finally {
    loading.dify = false
  }
}

async function testMcp() {
  mcpTesting.value = true
  mcpTestMsg.value = ''
  try {
    const r = await integrationApi.testMcp()
    const tools = r?.result?.tools
    if (Array.isArray(tools)) {
      mcpTestOk.value = true
      mcpTestMsg.value = `tools/list 正常, 返回 ${tools.length} 个工具: ${tools.map((t) => t.name).join(', ')}`
    } else {
      mcpTestOk.value = false
      mcpTestMsg.value = `响应异常 (期望 result.tools 数组): ${JSON.stringify(r).slice(0, 200)}`
    }
  } catch (e) {
    mcpTestOk.value = false
    mcpTestMsg.value = `探活失败: ${(e && (e.msg || e.message)) || '网络或鉴权错误'}`
  } finally {
    mcpTesting.value = false
  }
}

function copyDifyParams() {
  const text = [
    '=== Dify 对接参数 (ERP /api/workflow 触发) ===',
    `方向: ${dify.value.direction}`,
    `bot 账号: ${dify.value.botUsername} ${dify.value.botExists ? '(已创建)' : '(未创建, 先跑 sql/51 建账号)'}`,
    '',
    'HTTP 节点 1: 登录拿 token',
    `  POST /api/auth/login`,
    `  Body: {"username":"${dify.value.botUsername}","password":"<env>","client":"DIFY"}`,
    '',
    'HTTP 节点 2~N: 调 workflow (Header 带 Authorization: ${http_response.data.token})',
    ...(dify.value.endpoints || []).map((ep) => `  ${ep}`),
    '',
    `鉴权: ${dify.value.auth}`,
    `参考: ${dify.value.docs}`
  ].join('\n')
  navigator.clipboard?.writeText(text)
    .then(() => ElMessage.success('对接参数已复制到剪贴板'))
    .catch(() => ElMessage.warning('复制失败, 请手动选中复制'))
}

onMounted(() => { loadAi(); loadMcp(); loadDify() })
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.section-title { font-size: 15px; font-weight: 600; color: #333; }
.muted-tip { color: #909399; font-size: 12px; }
.page-card { background: #fff; padding: 16px; border-radius: 4px; box-shadow: 0 1px 4px rgba(0,21,41,0.04); }
code { background: #f0f2f5; padding: 2px 6px; border-radius: 3px; font-family: monospace; font-size: 12px; color: #409eff; }
/* v1.1.117 — 主/备 2 模型槽位 */
.slot-block {
  padding: 14px 16px 2px; margin-bottom: 12px;
  background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 10px;
}
.slot-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }

.card-foot { margin-top: 12px; }

/* v1.1.117 — 3 能力开关 (整行卡片 + 右侧勾选) */
.caps {
  display: flex; flex-direction: column; gap: 8px;
  margin-top: 4px; padding-top: 14px; border-top: 1px solid #ebeef5;
}
.cap-row {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 12px 16px; background: #fafbfc; border: 1px solid #ebeef5; border-radius: 8px;
  cursor: pointer; transition: all .18s;
  &:hover { border-color: #cfe0f2; }
  &.danger { background: #fdf6f6; border-color: #f5d9d9; }
  &.danger:hover { border-color: #eab8b8; }
}
.cap-text { min-width: 0; }
.cap-text b { display: block; font-size: 13px; font-weight: 600; color: #303133; }
.cap-text small { display: block; font-size: 12px; color: #909399; margin-top: 3px; }

.asr-key-row { margin-top: 8px; }

.tool-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tool-name { font-family: monospace; color: #409eff; font-size: 12px; background: #f0f2f5; padding: 1px 5px; border-radius: 3px; }
</style>
