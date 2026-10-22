import request from '@/utils/request'

export const userApi = {
  page: (params) => request.get('/system/user/page', { params }),
  detail: (id) => request.get(`/system/user/${id}`),
  add: (data) => request.post('/system/user', data),
  update: (data) => request.put('/system/user', data),
  delete: (id) => request.delete(`/system/user/${id}`),
  resetPwd: (id, newPwd) => request.post(`/system/user/${id}/resetPwd`, null, { params: { newPwd } }),
  getRoles: (id) => request.get(`/system/user/${id}/roles`),
  assignRoles: (id, roleIds) => request.put(`/system/user/${id}/roles`, roleIds),
  // P1-1: 改密必须传 oldPassword (后端 Service 内会判断是否本人/超管); 非本人超管必须校验 oldPassword.
  updatePassword: (id, password, oldPassword = '') => request.put(`/system/user/${id}/password`, { password, oldPassword }),
  // 当前用户改自己的密码
  changeOwnPassword: (oldPassword, newPassword) => request.put('/system/user/me/password', { oldPassword, newPassword })
}

export const roleApi = {
  page: (params) => request.get('/system/role/page', { params }),
  detail: (id) => request.get(`/system/role/${id}`),
  add: (data) => request.post('/system/role', data),
  update: (data) => request.put('/system/role', data),
  delete: (id) => request.delete(`/system/role/${id}`),
  menus: (id) => request.get(`/system/role/${id}/menus`),
  grantMenus: (id, menuIds) => request.put(`/system/role/${id}/menus`, menuIds),
  /** v1.0.10+: 按客户端类型查询已分配菜单 */
  menusByClient: (id) => request.get(`/system/role/${id}/menus/client`),
  /** v1.0.10+: 按客户端类型分配菜单权限 */
  grantMenusByClient: (id, clientType, menuIds) => request.put(`/system/role/${id}/menus/client?clientType=${clientType}`, menuIds),
  users: (id) => request.get(`/system/role/${id}/users`),
  assignUsers: (id, userIds) => request.put(`/system/role/${id}/users`, userIds)
}

export const menuApi = {
  list: () => request.get('/system/menu/list'),
  mine: () => request.get('/system/menu/mine'),
  add: (data) => request.post('/system/menu', data),
  update: (data) => request.put('/system/menu', data),
  delete: (id) => request.delete(`/system/menu/${id}`)
}

export const deptApi = {
  list: () => request.get('/system/dept/list'),
  tree: () => request.get('/system/dept/tree'),
  add: (data) => request.post('/system/dept', data),
  update: (data) => request.put('/system/dept', data),
  delete: (id) => request.delete(`/system/dept/${id}`)
}

export const configApi = {
  page: (params) => request.get('/system/config/page', { params }),
  detail: (id) => request.get(`/system/config/${id}`),
  add: (data) => request.post('/system/config', data),
  update: (data) => request.put('/system/config', data),
  delete: (id) => request.delete(`/system/config/${id}`),
  getByKey: (key) => request.get(`/system/config/key/${key}`),
  updateValue: (key, value) => request.put('/system/config/value', { key, value })
}

/**
 * 对接方式 (v1.1.80) — 系统设置「对接方式」tab 的状态端点 + LLM provider 顺序意图记录.
 * 状态端点只读 (脱敏), provider 顺序写 sys_config.AI_PROVIDER_ORDER (仅记录意图, 实际生效需重建 jar).
 */
export const integrationApi = {
  getAi: () => request.get('/system/integration/ai'),
  getMcp: () => request.get('/system/integration/mcp'),
  getDify: () => request.get('/system/integration/dify'),
  /**
   * v1.1.80 — 应用 AI 集成覆盖 (写 sys_config.AI_INTEGRATION JSON + 强刷 LLM 链, 即时生效).
   * json 形如 {"llm":{"providerOrder":"...","slot0":{...},"slot1":{...}},"asr":{"key":"...","model":"..."}}.
   * 空串 = 清除覆盖 (回落纯 env). 走 /system/integration/apply (非通用 /config/value, 顺带 rebuild LlmClient).
   */
  applyIntegration: (json) => request.post('/system/integration/apply', { json }),
  /**
   * v1.1.117 — 「模型接口配置」卡「测试连接」: 用表单里的 provider 配置发一次最小 chat 探活.
   * payload: {name, key, model, baseUrl, timeoutMs}. 纯探活, 不落库.
   * 返回 {ok, provider, latencyMs, errMsg}.
   */
  testLlm: (payload) => request.post('/system/integration/llm-test', payload),
  /** v1.1.80 前遗留的 provider 顺序意图记录 key (DB 覆盖上线后主路径改走 AI_INTEGRATION). */
  setProviderOrder: (order) => configApi.updateValue('AI_PROVIDER_ORDER', order),
  getProviderOrder: () => configApi.getByKey('AI_PROVIDER_ORDER'),
  /** MCP 探活: 直接对 /api/mcp 发 tools/list (当前登录 token 鉴权). */
  testMcp: () => request.post('/mcp', { jsonrpc: '2.0', id: Date.now(), method: 'tools/list', params: {} })
}

export const backupApi = {
  page: (params) => request.get('/system/backup/page', { params }),
  manual: () => request.post('/system/backup/manual'),
  restore: (id) => request.post(`/system/backup/restore/${id}`),
  delete: (id) => request.delete(`/system/backup/${id}`),
  factoryReset: () => request.post('/system/backup/factory-reset'),
  clearData: (tables) => request.post('/system/backup/clear', tables)
}

// 操作日志 (含删除数据快照)
export const operLogApi = {
  page: (params) => request.get('/system/oper-log/page', { params }),
  clean: (params) => request.delete('/system/oper-log/clean', { params })
}

// 登录日志
export const loginLogApi = {
  page: (params) => request.get('/system/login-log/page', { params })
}

// 打印模板 (myprint-design)
export const printTemplateApi = {
  page: (params) => request.get('/system/print-template/page', { params }),
  detail: (id) => request.get(`/system/print-template/${id}`),
  getByBizType: (bizType, customerId) => {
    const params = customerId ? { customerId } : undefined
    return request.get(`/system/print-template/biz-type/${bizType}`, { params })
  },
  add: (data) => request.post('/system/print-template', data),
  update: (data) => request.put('/system/print-template', data),
  delete: (id) => request.delete(`/system/print-template/${id}`)
}
