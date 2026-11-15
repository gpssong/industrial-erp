/**
 * 工作台首屏加载编排 — 把串行等待收敛成两轮并行.
 *
 * 背景: 原来 dashboard/index.vue 逐个 await (模块配置 → 区块配置 → /me → KPI →
 *   库存预警 → 6 类待办查询), 共 11 次串行往返. 厂区 WiFi 不明显, 4G 下每次往返
 *   200~600ms, 首屏就要等好几秒.
 *
 * 依赖关系只保留真实存在的两条:
 *   ① /me 与两份配置互不依赖 → 同一轮并行;
 *   ② KPI / 库存预警 / 6 类待办互不依赖 (待办内部的区块也已彼此独立) → 同一轮并行.
 * 区块开关/权限门禁由调用方在构造 blocks 时决定, 这里不感知业务语义.
 */

/**
 * 并行发起所有启用的待办块, 按声明顺序合并结果.
 * 单块失败/同步抛错只丢弃该块, 不影响其它块 (R13 静默语义).
 *
 * @param {Array<{key: string, enabled: boolean, run: () => Promise<Array>|Array}>} blocks
 * @returns {Promise<Array>} 按声明顺序拼接的各块结果
 */
export async function runTodoBlocks(blocks) {
  const active = (Array.isArray(blocks) ? blocks : []).filter(b => b && b.enabled)
  const settled = await Promise.allSettled(active.map(b => (async () => b.run())()))
  const out = []
  for (const r of settled) {
    if (r.status === 'fulfilled' && Array.isArray(r.value)) out.push(...r.value)
  }
  return out
}

/**
 * 首屏启动轮: 模块开关 / 工作台区块开关 / 当前用户权限 三者并行.
 * 任一项失败都不影响其它项 (原代码里各自 try/catch 静默).
 *
 * @param {{loadModuleConfig: Function, loadDashboardConfig: Function, me: Function}} deps
 * @returns {Promise<{moduleConfigOk: boolean, dashboardConfigOk: boolean, me: object|null}>}
 */
export async function loadBootstraps({ loadModuleConfig, loadDashboardConfig, me }) {
  const [m, d, u] = await Promise.allSettled([
    (async () => loadModuleConfig())(),
    (async () => loadDashboardConfig())(),
    (async () => me())()
  ])
  return {
    moduleConfigOk: m.status === 'fulfilled' && m.value !== false,
    dashboardConfigOk: d.status === 'fulfilled' && d.value !== false,
    me: u.status === 'fulfilled' ? (u.value ?? null) : null
  }
}

export default { runTodoBlocks, loadBootstraps }
