/**
 * v1.1.130+ App 工作台区块开关 (admin 全局一刀切) — App 端运行时查询.
 *
 * 用法 (与 appModuleEnabled.js 并列):
 *   import { isSectionEnabled, isGroupEnabled, loadFromServer } from '@/utils/appDashboardConfig'
 *
 * 存储: sys_config 表, config_key = APP_DASHBOARD_CONFIG, config_value = JSON 字符串.
 *   {
 *     allFunc:  { enabled: true, groups: { '生产':true, '采购':true, ..., '经营报表':true } },
 *     kpi:      { enabled: true },
 *     warning:  { enabled: true },
 *     todo:     { enabled: true }
 *   }
 *   全部功能子分组名与 dashboard/index.vue 的 FUNC_GROUP_ORDER 严格对齐.
 *
 * 兜底语义 (R13 静默失败, 开箱即用):
 *   - loadFromServer 失败 / 未完成 / 未配置 → isSectionEnabled / isGroupEnabled 返回 true (全开)
 *   - 已配置且 enabled===false → false (该区块不渲染)
 *   - 分组开关: 组 off 或 全部功能总开关 off → 该分组隐藏
 *
 * 与 appModuleEnabled (业务模块级) 的关系: 两者叠加.
 *   区块开关 = admin 决定工作台"显示哪些卡片/标签"; 业务模块开关 = admin 决定 App 端
 *   "开放哪些业务模块" (含 tabBar/详情/入口). 区块关闭时对应数据请求也跳过 (省流量).
 */
import { reactive } from 'vue'

// 4 个固定区块 key (与 seed SQL + PC AppDashboardConfig.vue 对齐)
export const SECTION_KEYS = ['allFunc', 'kpi', 'warning', 'todo']

// 全部功能 7 子分组 (与 dashboard/index.vue FUNC_GROUP_ORDER 严格对齐, 顺序即渲染顺序)
export const FUNC_GROUPS = ['生产', '采购', '销售', '库存', 'XK记录', '现场填报', '经营报表']

const DEFAULT_GROUPS = FUNC_GROUPS.reduce((m, g) => { m[g] = true; return m }, {})

// 内存 cache (reactive, 默认全开 — 首次加载/未配置前也全开)
const state = reactive({
  allFunc: { enabled: true, groups: { ...DEFAULT_GROUPS } },
  kpi:     { enabled: true },
  warning: { enabled: true },
  todo:    { enabled: true }
})
const meta = { loaded: false, lastFetch: 0, fetching: false, lastErr: null }
const CACHE_TTL_MS = 5 * 60 * 1000  // 5 min (与 appModuleEnabled 一致)

/**
 * 拉取最新配置 (5min 内不重复, 失败 fallback 全开)
 * @param {boolean} force true=绕过 TTL 强制刷新
 * @returns {Promise<boolean>} true=拉取成功, false=失败/跳过
 */
export async function loadFromServer(force = false) {
  if (meta.fetching) return false
  const now = Date.now()
  if (!force && meta.loaded && (now - meta.lastFetch) < CACHE_TTL_MS) return true

  meta.fetching = true
  try {
    // 走共享 request, 拦截器自动塞 X-Client-Type: APP header
    const api = (await import('@/api/index.js')).default
    const r = await api.appDashboardGetConfig()
    // App 端拦截器已解包 R.data — r 即业务值 (config_value 的 JSON 字符串或 null)
    if (typeof r === 'string' && r) {
      try {
        applyConfig(JSON.parse(r))
        meta.loaded = true
        meta.lastFetch = now
        meta.lastErr = null
        return true
      } catch (e) {
        meta.lastErr = 'JSON 解析失败: ' + e.message
      }
    } else if (r === null || r === '' || r === undefined) {
      // 后端没配 APP_DASHBOARD_CONFIG → 保留默认 (全开)
      meta.loaded = true
      meta.lastFetch = now
      meta.lastErr = null
      return true
    }
    meta.lastErr = '返回数据非字符串'
    return false
  } catch (e) {
    meta.lastErr = (e && e.msg) || (e && e.message) || '网络失败'
    console.warn('[appDashboardConfig] loadFromServer 失败, fallback 全开:', meta.lastErr)
    return false
  } finally {
    meta.fetching = false
  }
}

function applyConfig(parsed) {
  if (!parsed || typeof parsed !== 'object') return
  for (const k of SECTION_KEYS) {
    const entry = parsed[k]
    if (entry && typeof entry === 'object') {
      state[k].enabled = entry.enabled !== false
    }
  }
  // 分组: 只覆盖存在的分组, 缺失的保持默认 (true)
  const groups = parsed.allFunc && parsed.allFunc.groups
  if (groups && typeof groups === 'object') {
    for (const g of FUNC_GROUPS) {
      if (typeof groups[g] === 'boolean') state.allFunc.groups[g] = groups[g]
    }
  }
}

/** 区块级开关: true = 显示. 未拉取/未配置 → true (全开). */
export function isSectionEnabled(key) {
  const entry = state[key]
  if (!entry) return true
  return entry.enabled !== false
}

/** 全部功能某子分组开关: 需全部功能总开关开 + 该分组开关开. */
export function isGroupEnabled(group) {
  if (!isSectionEnabled('allFunc')) return false
  const g = state.allFunc.groups[group]
  if (typeof g === 'boolean') return g
  return true
}

/** 给 dashboard 调试用, 暴露原始 reactive state. */
export function getConfig() { return state }

/** 元信息 (loaded/lastFetch/lastErr), 用于诊断. */
export function getMeta() { return meta }
