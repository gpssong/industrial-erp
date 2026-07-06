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
  updatePassword: (id, password) => request.put(`/system/user/${id}/password`, { password })
}

export const roleApi = {
  page: (params) => request.get('/system/role/page', { params }),
  detail: (id) => request.get(`/system/role/${id}`),
  add: (data) => request.post('/system/role', data),
  update: (data) => request.put('/system/role', data),
  delete: (id) => request.delete(`/system/role/${id}`),
  menus: (id) => request.get(`/system/role/${id}/menus`),
  grantMenus: (id, menuIds) => request.put(`/system/role/${id}/menus`, menuIds),
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

export const printApi = {
  page: (params) => request.get('/system/print/page', { params }),
  detail: (id) => request.get(`/system/print/${id}`),
  add: (data) => request.post('/system/print', data),
  update: (data) => request.put('/system/print', data),
  delete: (id) => request.delete(`/system/print/${id}`)
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
