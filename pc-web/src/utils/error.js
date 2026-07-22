/**
 * 标准化错误对象 — 统一前后端错误展示 (P2-10)
 *
 * <p>设计目标:
 * <ul>
 *   <li>页面只展示 userMessage (面向用户的中文提示)</li>
 *   <li>technicalMessage / stack 仅上报到 Sentry 等受控日志, 不进 console</li>
 *   <li>支持 requestId 关联服务端日志</li>
 *   <li>toast 重复拦截 (全局拦截器已 toast, 页面 catch 不再二次弹)</li>
 * </ul>
 */
export class StandardError extends Error {
  constructor({ code, userMessage, technicalMessage, requestId, status, source }) {
    super(userMessage || technicalMessage || '操作失败')
    this.code = code
    this.userMessage = userMessage || technicalMessage || '操作失败'
    this.technicalMessage = technicalMessage || ''
    this.requestId = requestId || null
    this.status = status || null
    this.source = source || 'unknown'  // 'api' | 'network' | 'business'
  }
}

/**
 * 把任意 thrown value 转成 StandardError.
 * 兼容: AxiosError / 后端 R<data> {code,msg} / fetch network error / 普通 Error
 */
export function toStandardError(e) {
  if (e instanceof StandardError) return e
  // AxiosError
  if (e && e.isAxiosError) {
    const status = e.response?.status
    const data = e.response?.data
    const code = data?.code
    const msg = data?.msg || data?.message
    const requestId = data?.requestId || e.response?.headers?.['x-request-id']
    let userMessage = msg || e.message || '操作失败'
    if (e.code === 'ERR_NETWORK') userMessage = '无法连接服务器, 请检查网络或 API 地址'
    if (e.code === 'ECONNABORTED') userMessage = '请求超时, 请重试'
    if (status === 401) userMessage = '登录已过期, 请重新登录'
    if (status === 403) userMessage = '权限不足'
    if (status === 404) userMessage = '资源不存在'
    if (status >= 500) userMessage = '服务器异常, 请稍后重试'
    return new StandardError({
      code, userMessage,
      technicalMessage: msg || e.message,
      requestId, status,
      source: status ? 'api' : 'network'
    })
  }
  // fetch 网络错误
  if (e && (e.name === 'TypeError' || e instanceof TypeError)) {
    return new StandardError({
      code: 'NETWORK_ERROR',
      userMessage: '无法连接服务器',
      technicalMessage: e.message,
      source: 'network'
    })
  }
  // 普通 Error
  return new StandardError({
    code: e?.code,
    userMessage: e?.msg || e?.message || '操作失败',
    technicalMessage: e?.stack,
    source: 'business'
  })
}

/**
 * 全局错误 toast 标志位 — 防止 request.js 拦截器 + 页面 catch 双重弹
 * request.js 拦截器已弹 toast 时, 抛出的 Error 会被标记 ALREADY_TOASTED.
 */
export const ERR_ALREADY_TOASTED = '__ERR_ALREADY_TOASTED__'
