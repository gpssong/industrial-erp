/**
 * v1.1.80+ App 模块开关 (admin 全局一刀切) — App 端运行时查询.
 *
 * 用法:
 *   import { isModuleEnabled, isPermEnabled, loadFromServer } from '@/utils/appModuleEnabled'
 *
 * 兜底语义 (R13 静默失败):
 *   - loadFromServer 失败 / 未完成时, isModuleEnabled 返回 true (与"开箱即用"对齐)
 *   - loadFromServer 成功后, 未配置模块默认 true
 *   - 5 min 内不重复打网络 (force=true 强制刷新)
 *
 * 数据形态 (与后端 AppModuleConfigDTO 对应):
 *   {
 *     purchase:   { enabled: true,  overrides: {} },
 *     sales:      { enabled: true,  overrides: { 'sales:delivery:list': false } },
 *     ...
 *   }
 *
 * isPermEnabled(moduleKey, perm) 优先级:
 *   1. module.enabled === false → false (模块级关, 子功能一律不可用)
 *   2. overrides[perm] === false → false (子功能显式关)
 *   3. overrides[perm] === true  → true  (子功能显式开)
 *   4. undefined (未 override)    → 跟随 module.enabled (默认 true)
 */
import { ref, reactive } from 'vue'

// 8 个固定模块 key (与后端 AppModuleConfigDTO.DEFAULT_KEYS 对齐)
export const MODULE_KEYS = [
  'purchase', 'sales', 'inventory', 'production',
  'base', 'finance', 'report', 'workflow'
]

// 内存 cache (reactive, App 端任何页面 import 后能立即响应)
const config = reactive({})  // { purchase: { enabled, overrides }, ... }
const meta = { loaded: false, lastFetch: 0, fetching: false, lastErr: null }
const CACHE_TTL_MS = 5 * 60 * 1000  // 5 min

/**
 * 拉取最新配置 (5min 内不重复, 失败 fallback 默认全开)
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
    const r = await api.appModuleGetConfig()
    // 后端 R<String>: { code: 200, data: "<JSON string>" }
    const raw = r && (r.data !== undefined ? r.data : r)
    if (typeof raw === 'string' && raw) {
      try {
        const parsed = JSON.parse(raw)
        applyConfig(parsed)
        meta.loaded = true
        meta.lastFetch = now
        meta.lastErr = null
        return true
      } catch (e) {
        meta.lastErr = 'JSON 解析失败: ' + e.message
      }
    } else if (raw === null || raw === '' || raw === undefined) {
      // 后端没配 APP_MODULE_ENABLED → 保留默认 (空对象 = 全开)
      meta.loaded = true
      meta.lastFetch = now
      meta.lastErr = null
      return true
    }
    meta.lastErr = '返回数据非字符串'
    return false
  } catch (e) {
    // 拦截器已弹过 toast, 这里只记录, 不再二次弹
    meta.lastErr = (e && e.msg) || (e && e.message) || '网络失败'
    console.warn('[appModuleEnabled] loadFromServer 失败, fallback 全开:', meta.lastErr)
    return false
  } finally {
    meta.fetching = false
  }
}

function applyConfig(parsed) {
  if (!parsed || typeof parsed !== 'object') return
  // 规范化: 8 模块必须全在, 缺失的补默认 (true)
  for (const k of MODULE_KEYS) {
    const entry = parsed[k]
    if (entry && typeof entry === 'object') {
      config[k] = {
        enabled: entry.enabled !== false,
        overrides: (entry.overrides && typeof entry.overrides === 'object') ? entry.overrides : {}
      }
    } else {
      config[k] = { enabled: true, overrides: {} }
    }
  }
}

/**
 * 模块级开关: true = 启用, false = 停用
 * 兜底: 拉取失败 / 未配置 → true (开箱即用, R13 静默失败)
 */
export function isModuleEnabled(key) {
  const entry = config[key]
  if (!entry) return true  // 默认全开
  return entry.enabled !== false
}

/**
 * 子功能 perm 级开关: 优先级见文件头
 * 兜底: 同 isModuleEnabled → true
 */
export function isPermEnabled(moduleKey, perm) {
  if (!isModuleEnabled(moduleKey)) return false
  const entry = config[moduleKey]
  if (!entry || !entry.overrides) return true
  const v = entry.overrides[perm]
  if (v === false) return false
  // undefined / true 都视为跟随 enabled (已通过 isModuleEnabled 短路到 true)
  return true
}

/**
 * 给 dashboard 调试用, 暴露原始 reactive config.
 * 注意: 浅引用, 不要直接 mutate.
 */
export function getConfig() { return config }

/**
 * 元信息 (loaded/lastFetch/lastErr), 用于诊断页面
 */
export function getMeta() { return meta }

/**
 * v1.1.80+: 页面级"模块停用"短路守卫. 用于 onMounted 第一行:
 *   onMounted(() => { if (!guardPage('sales')) return; loadData() })
 *
 * 行为:
 *   - 模块 enabled=true (或未拉取到配置) → 返回 true, 正常执行
 *   - 模块 enabled=false → toast「该模块已停用」+ switchTab 工作台 + 返回 false
 *
 * 注意: 必须先 await loadFromServer() 才能拿到最新状态. App.vue onLaunch 已预热,
 *       99% 场景已是最新. 若担心首屏 race condition, 在守卫前显式 await loadFromServer().
 */
export function guardPage(moduleKey) {
  if (isModuleEnabled(moduleKey)) return true
  try {
    if (typeof uni !== 'undefined') {
      if (uni.showToast) {
        uni.showToast({ title: '该模块已停用', icon: 'none', duration: 2000 })
      }
      // 延迟 200ms 跳转, 让 toast 弹完 (R12: switchTab 立即切页会打断 toast)
      setTimeout(() => {
        try { uni.switchTab({ url: '/pages/dashboard/index' }) } catch (e) { /* ignore */ }
      }, 200)
    }
  } catch (e) { /* ignore */ }
  return false
}
