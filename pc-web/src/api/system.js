import request from '@/utils/request'

export const userApi = {
  page: (params) => request.get('/system/user/page', { params }),
  detail: (id) => request.get(`/system/user/${id}`),
  add: (data) => request.post('/system/user', data),
  update: (data) => request.put('/system/user', data),
  delete: (id) => request.delete(`/system/user/${id}`),
  resetPwd: (id, newPwd) => request.post(`/system/user/${id}/resetPwd`, null, { params: { newPwd } }),
  getRoles: (id) => request.get(`/system/user/${id}/roles`),
  assignRoles: (id, roleIds) => request.put(`/system/user/${id}/roles`, roleIds)
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
  mine: () => request.get('/system/menu/mine')
}

export const deptApi = {
  list: () => request.get('/system/dept/list'),
  tree: () => request.get('/system/dept/tree')
}
