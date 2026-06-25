<template>
  <div class="container">
    <div class="card">
      <div class="title">👥 用户管理</div>
      <div class="row" style="gap:8px;margin-bottom:10px">
        <input class="input" v-model="keyword" placeholder="搜索用户名/姓名" @confirm="loadData" style="flex:1" />
        <button class="btn" @click="loadData" style="width:auto;padding:8px 16px">搜索</button>
        <button class="btn" @click="onAdd" style="width:auto;padding:8px 16px;background:#27ae60">+ 新增</button>
      </div>
    </div>

    <div class="card" v-for="u in list" :key="u.id">
      <div class="row">
        <div>
          <div style="font-weight:bold">{{ u.nickname || u.username }}</div>
          <div class="muted">{{ u.username }} · {{ u.deptName || '未分配部门' }}</div>
        </div>
        <div class="row" style="gap:6px">
          <span class="tag" :class="u.status === 1 ? 'tag-ok' : 'tag-off'">{{ u.status === 1 ? '正常' : '停用' }}</span>
          <span class="tag tag-admin" v-if="u.isAdmin === 1">超管</span>
        </div>
      </div>
      <div class="row" style="margin-top:8px;gap:6px">
        <button class="btn-sm" @click="onEdit(u)">编辑</button>
        <button class="btn-sm btn-warn" @click="onResetPwd(u)">重置密码</button>
        <button class="btn-sm btn-danger" @click="onDelete(u)" v-if="u.id !== 1">删除</button>
      </div>
    </div>

    <div v-if="!list.length" class="empty">暂无用户数据</div>

    <!-- 编辑弹窗 -->
    <div v-if="showEdit" class="mask" @click.self="showEdit = false">
      <div class="dialog">
        <div class="dialog-title">{{ editForm.id ? '编辑用户' : '新增用户' }}</div>
        <div class="form-item"><label class="label">用户名</label><input class="input" v-model="editForm.username" :disabled="!!editForm.id" /></div>
        <div class="form-item" v-if="!editForm.id"><label class="label">密码</label><input class="input" type="password" v-model="editForm.password" /></div>
        <div class="form-item"><label class="label">昵称</label><input class="input" v-model="editForm.nickname" /></div>
        <div class="form-item"><label class="label">真实姓名</label><input class="input" v-model="editForm.realName" /></div>
        <div class="form-item"><label class="label">手机号</label><input class="input" v-model="editForm.phone" /></div>
        <div class="form-item">
          <label class="label">状态</label>
          <select class="input" v-model="editForm.status">
            <option :value="1">正常</option>
            <option :value="0">停用</option>
          </select>
        </div>
        <div class="form-item">
          <label class="label">角色</label>
          <div style="display:flex;flex-wrap:wrap;gap:6px">
            <label v-for="r in roles" :key="r.id" style="display:flex;align-items:center;gap:4px;font-size:13px">
              <input type="checkbox" :value="r.id" v-model="selectedRoles" /> {{ r.roleName }}
            </label>
          </div>
        </div>
        <div class="row" style="margin-top:12px;gap:8px">
          <button class="btn" @click="onSave" style="flex:1">保存</button>
          <button class="btn btn-outline" @click="showEdit = false" style="flex:1">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import api from '../../api/index.js'

const keyword = ref('')
const list = ref([])
const roles = ref([])
const showEdit = ref(false)
const editForm = ref({})
const selectedRoles = ref([])

function toast(msg) { alert(msg) }

async function loadData() {
  try {
    const r = await api.userPage({ pageNum: 1, pageSize: 100, username: keyword.value || undefined })
    list.value = (r && r.records) || []
  } catch (e) { list.value = [] }
}

async function loadRoles() {
  try {
    const r = await api.rolePage({ pageNum: 1, pageSize: 100 })
    roles.value = (r && r.records) || []
  } catch (e) { roles.value = [] }
}

function onAdd() {
  editForm.value = { username: '', password: '', nickname: '', realName: '', phone: '', status: 1 }
  selectedRoles.value = []
  showEdit.value = true
}

async function onEdit(u) {
  editForm.value = { ...u }
  showEdit.value = true
  try {
    const r = await api.userGetRoles(u.id)
    selectedRoles.value = r || []
  } catch (e) { selectedRoles.value = [] }
}

async function onSave() {
  const f = editForm.value
  if (!f.username) return toast('请输入用户名')
  if (!f.id && !f.password) return toast('请输入密码')
  try {
    if (f.id) {
      await api.userUpdate(f)
      await api.userAssignRoles(f.id, selectedRoles.value)
      toast('修改成功')
    } else {
      const id = await api.userAdd(f)
      if (id) await api.userAssignRoles(id, selectedRoles.value)
      toast('新增成功')
    }
    showEdit.value = false
    loadData()
  } catch (e) {
    toast('操作失败: ' + (e.msg || e.message || '未知错误'))
  }
}

async function onDelete(u) {
  if (!confirm('确定删除用户 ' + (u.nickname || u.username) + '?')) return
  try {
    await api.userDelete(u.id)
    toast('删除成功')
    loadData()
  } catch (e) { toast('删除失败: ' + (e.msg || e.message)) }
}

async function onResetPwd(u) {
  if (!confirm('确定重置 ' + (u.nickname || u.username) + ' 的密码为 123456?')) return
  try {
    await api.userResetPwd(u.id)
    toast('密码已重置为 123456')
  } catch (e) { toast('重置失败: ' + (e.msg || e.message)) }
}

onMounted(() => { loadData(); loadRoles() })
</script>
<style scoped>
.container { padding: 12px; }
.card { background: #fff; border-radius: 8px; padding: 14px; margin-bottom: 10px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.title { font-size: 16px; font-weight: bold; color: #333; margin-bottom: 8px; }
.row { display: flex; justify-content: space-between; align-items: center; }
.muted { color: #999; font-size: 12px; margin-top: 2px; }
.empty { text-align: center; color: #999; padding: 40px; }
.input { width: 100%; height: 36px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 10px; box-sizing: border-box; font-size: 14px; }
.input:focus { border-color: #1e6091; outline: none; }
.btn { background: #1e6091; color: #fff; padding: 10px 20px; border-radius: 6px; border: none; cursor: pointer; font-size: 15px; width: 100%; }
.btn:hover { background: #2980b9; }
.btn-outline { background: transparent; color: #1e6091; border: 1px solid #1e6091; }
.btn-sm { background: #1e6091; color: #fff; padding: 4px 12px; border-radius: 4px; border: none; cursor: pointer; font-size: 12px; }
.btn-warn { background: #e67e22; }
.btn-danger { background: #c0392b; }
.tag { padding: 2px 8px; border-radius: 4px; font-size: 11px; }
.tag-ok { background: #e8f5e9; color: #2e7d32; }
.tag-off { background: #fbe9e7; color: #c62828; }
.tag-admin { background: #e3f2fd; color: #1565c0; }
.mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); z-index: 9999; display: flex; align-items: center; justify-content: center; }
.dialog { background: #fff; border-radius: 12px; padding: 20px; width: 90%; max-width: 400px; max-height: 80vh; overflow-y: auto; }
.dialog-title { font-size: 18px; font-weight: bold; margin-bottom: 12px; }
.form-item { margin: 8px 0; }
.label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }
</style>
