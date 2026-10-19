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

    <!-- 卡片 1: LLM 大模型网关 -->
    <div class="page-card" style="margin-bottom:16px" v-loading="loading.ai">
      <div class="card-head">
        <div class="section-title">🤖 LLM 大模型网关 <el-tag :type="llm.enabled ? 'success' : 'info'" size="small" style="margin-left:8px">{{ llm.enabled ? '已启用' : '未启用' }}</el-tag></div>
        <el-button size="small" @click="loadAi" :loading="loading.ai">刷新</el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px">
        实际 provider 顺序由 <b>DB <code>sys_config.AI_INTEGRATION</code></b> 或 env <code>ERP_AI_PROVIDERS</code> 决定 (DB 优先)。
        下方开关 + 填 key 后点「保存并即时生效」即写入 DB 并立即切换 provider 链, <b>无需重建 jar</b>。
      </el-alert>

      <!-- provider 槽位 -->
      <div class="providers">
        <div v-for="p in llm.providers" :key="p.slot" class="provider-row">
          <div class="p-left">
            <div class="p-name">
              <el-tag size="small" :type="p.configured ? 'success' : 'info'" effect="plain">{{ p.name }}</el-tag>
              <span class="p-model">{{ p.model }}</span>
              <el-tag v-if="p.slot === 0" size="small" type="warning" effect="plain">主</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">备</el-tag>
              <el-tag v-if="p.source === 'DB'" size="small" type="success" effect="plain">DB 覆盖</el-tag>
            </div>
            <div class="p-meta">
              <span class="muted-tip">key: {{ p.keyMasked }}</span>
              <span class="muted-tip" style="margin-left:12px">base_url: {{ p.baseUrl }}</span>
            </div>
            <div class="p-key-row">
              <el-input v-model="integration.llm.slotKeys[p.name]" placeholder="API key (留空 = 回落 env)"
                type="password" show-password size="small" style="width:260px" />
            </div>
          </div>
          <div class="p-switch">
            <el-switch v-model="providerOrder[p.name]" :active-value="true" :inactive-value="false"
              @change="onProviderToggle" />
            <span class="muted-tip" style="margin-left:8px">{{ p.configured ? '' : '未配置 key' }}</span>
          </div>
        </div>
      </div>

      <div class="card-foot">
        <el-button type="primary" size="small" :loading="savingOrder" @click="saveIntegration">保存并即时生效</el-button>
        <el-button size="small" @click="resetToEnv">还原 (纯 env)</el-button>
        <span class="muted-tip" style="margin-left:12px">
          生效顺序: <code>{{ liveOrderStr }}</code> · 当前命中: <code>{{ llm.activeProvider || '—' }}</code>
        </span>
      </div>
      <el-alert v-if="!llm.dbOverrideActive" type="info" :closable="false" show-icon style="margin-top:10px">
        当前走 backend env (<code>ERP_AI_*</code>)。在上方填 API key 或切换顺序后点「保存并即时生效」, 即写入 <code>sys_config.AI_INTEGRATION</code>
        并立即切换 provider 链 (无需重建 jar); 未填字段自动回落 env。
      </el-alert>
      <el-alert v-else type="success" :closable="false" show-icon style="margin-top:10px">
        当前生效配置来自 <code>sys_config.AI_INTEGRATION</code> (DB 覆盖, 即时生效)。点「还原 (纯 env)」可清除覆盖。
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
const savingOrder = ref(false)
const mcpTesting = ref(false)
const mcpTestMsg = ref('')
const mcpTestOk = ref(false)

/** LLM 网关状态 (脱敏) */
const llm = ref({ enabled: false, providers: [], providerOrder: '', activeProvider: '—', liveProviderOrder: [], dbOverrideActive: false })
/** provider 顺序开关: name → 是否在链中 */
const providerOrder = reactive({})
/** v1.1.80: 可编辑的 AI_INTEGRATION 覆盖 (填 key 即时生效). 空字段 = 回落 env. */
const integration = reactive({
  llm: { slotKeys: {} },
  asr: { key: '' }
})
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
    // 初始化 provider 顺序开关 (按后端 liveProviderOrder 即时顺序)
    const live = llm.value.liveProviderOrder || []
    for (const p of d.llm?.providers || []) {
      providerOrder[p.name] = live.includes(p.name)
    }
    // 预填可编辑 key (后端返回的是脱敏值, 留空则保存时不覆盖, 回落 env)
    for (const p of d.llm?.providers || []) {
      integration.llm.slotKeys[p.name] = ''
    }
    integration.asr.key = ''
  } catch (e) {
    console.warn('[Integration] 加载 AI 状态失败:', e)
  } finally {
    loading.ai = false; loading.asr = false
  }
}

/** v1.1.80: 保存 AI_INTEGRATION 覆盖 → 即时生效 (写 DB + 强刷 LLM 链, 无需重建 jar). */
async function saveIntegration() {
  await ElMessageBox.confirm(
    '保存后, LLM/ASR 的 provider 顺序与 key 立即按新配置生效 (写入 sys_config.AI_INTEGRATION, 无需重建 jar)。'
    + '留空的字段自动回落 backend env。确定?',
    '即时生效', { type: 'warning', confirmButtonText: '保存并生效' }
  ).catch(() => { throw new Error('cancel') })

  savingOrder.value = true
  try {
    // 组装 AI_INTEGRATION JSON
    const llmOrder = Object.keys(providerOrder).filter((k) => providerOrder[k])
    const slot0 = findSlotByName(llm.value.providers, 0)
    const slot1 = findSlotByName(llm.value.providers, 1)
    const payload = {
      llm: {
        providerOrder: llmOrder.join(','),
        slot0: {},
        slot1: {}
      },
      asr: {}
    }
    // 只在用户填了 key 时才覆盖 (空 = 回落 env)
    for (const [slotIdx, slotName, src] of [[0, 'slot0', slot0], [1, 'slot1', slot1]]) {
      const key = integration.llm.slotKeys[src?.name]
      if (key && key.trim()) payload.llm[slotName] = { key: key.trim() }
    }
    if (integration.asr.key && integration.asr.key.trim()) {
      payload.asr.key = integration.asr.key.trim()
    }
    // 全空 (纯 env) → 存空串清除覆盖
    const isEmpty =
      !payload.llm.providerOrder && !payload.llm.slot0.key && !payload.llm.slot1.key
      && !payload.asr.key
    const json = isEmpty ? '' : JSON.stringify(payload)

    const resp = await integrationApi.applyIntegration(json)
    const rdata = resp?.data || {}
    await loadAi()   // 重新拉状态 (DB 已生效)
    ElMessage.success(
      json
        ? `已即时生效。生效顺序: ${(rdata.providerOrder || []).join(', ') || '(空)'} · 当前命中: ${rdata.activeProvider || '—'}`
        : '已清除 DB 覆盖, 回落纯 env (即时生效)'
    )
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    savingOrder.value = false
  }
}

function findSlotByName(providers, slot) {
  return (providers || []).find((p) => p.slot === slot)
}

/** 还原 = 清除 DB 覆盖 (回落纯 env). */
async function resetToEnv() {
  await ElMessageBox.confirm('清除 sys_config.AI_INTEGRATION 覆盖, 回落纯 env 配置? 即时生效。', '还原', { type: 'warning' })
    .catch(() => { throw new Error('cancel') })
  savingOrder.value = true
  try {
    await integrationApi.applyIntegration('')
    await loadAi()
    ElMessage.success('已还原为纯 env 配置')
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '还原失败')
  } finally {
    savingOrder.value = false
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

function onProviderToggle() {
  // 开关只改本地 reactive, 点「保存并即时生效」才写 DB + 强刷 LLM 链
  mcpTestMsg.value = ''
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
.card-foot { margin-top: 12px; }

.providers { display: flex; flex-direction: column; gap: 10px; }
.provider-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 16px; background: #fafbfc; border: 1px solid #ebeef5; border-radius: 4px;
}
.p-name { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.p-model { font-family: monospace; color: #606266; }
.p-meta { margin-top: 4px; }
.p-switch { display: flex; align-items: center; }
.p-key-row { margin-top: 6px; }
.asr-key-row { margin-top: 8px; }

.tool-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tool-name { font-family: monospace; color: #409eff; font-size: 12px; background: #f0f2f5; padding: 1px 5px; border-radius: 3px; }
</style>
