<template>
  <div class="login-container">
    <div class="login-box">
      <div class="login-header">
        <h1 class="industrial-title">企业级工业 ERP</h1>
        <p>薄膜 / 塑料 / 五金 / 加工 / 工贸一体</p>
      </div>
      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="onLogin" label-width="80px" size="large">
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" placeholder="请输入账号" clearable>
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password
                    @keyup.enter="onLogin">
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.remember">记住我</el-checkbox>
        </el-form-item>
        <el-button type="primary" @click="onLogin" :loading="loading" style="width:100%">登 录</el-button>
      </el-form>

      <!-- 服务器连接设置 (折叠面板) -->
      <div class="server-config">
        <div class="toggle-row" @click="serverPanelOpen = !serverPanelOpen">
          <el-icon><Setting /></el-icon>
          <span>服务器连接设置</span>
          <el-icon class="toggle-arrow" :class="{ open: serverPanelOpen }"><ArrowDown /></el-icon>
          <span class="current-server">{{ currentServer }}</span>
        </div>
        <el-collapse-transition>
          <div v-show="serverPanelOpen" class="server-panel">
            <el-form label-width="80px" size="small">
              <el-form-item label="API 地址">
                <el-input v-model="serverBase" placeholder="如 http://home.93gushi.com/api" clearable>
                  <template #append>
                    <el-button @click="saveServerBase">保存</el-button>
                  </template>
                </el-input>
              </el-form-item>
              <div class="server-tip">
                当前：{{ currentServer }}
                <el-link v-if="serverBase" type="danger" :underline="false" @click="resetServerBase" style="margin-left:8px">
                  恢复默认
                </el-link>
              </div>
            </el-form>
          </div>
        </el-collapse-transition>
      </div>

      <div class="login-tips">
        <span v-if="isDev">默认账号: <b>admin</b> / 密码: <b>admin123</b></span>
      </div>
    </div>
    <div class="login-bg"></div>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessage, ElCollapseTransition } from 'element-plus'
import { User, Lock, Setting, ArrowDown } from '@element-plus/icons-vue'

const router = useRouter()
const user = useUserStore()
const formRef = ref()
const loading = ref(false)

// 仅在开发模式下默认填入账号, 生产环境要求用户手动输入
const isDev = import.meta.env.DEV

const form = reactive({
  username: isDev ? 'admin' : '',
  password: isDev ? 'admin123' : '',
  remember: true
})

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// 服务器连接设置 (从原系统设置迁移至此, 登录前即可修改)
const serverBase = ref(localStorage.getItem('erp_api_base') || '')
const serverPanelOpen = ref(false)

const currentServer = computed(() => {
  const saved = localStorage.getItem('erp_api_base')
  if (saved) return saved
  return import.meta.env.VITE_API_BASE || (import.meta.env.DEV ? '/api (Vite 代理)' : '/api')
})

function saveServerBase() {
  const val = (serverBase.value || '').trim()
  if (val) {
    localStorage.setItem('erp_api_base', val)
    ElMessage.success('服务器地址已保存，下次登录生效')
  } else {
    localStorage.removeItem('erp_api_base')
    ElMessage.info('已恢复默认地址')
  }
}

function resetServerBase() {
  serverBase.value = ''
  localStorage.removeItem('erp_api_base')
  ElMessage.info('已恢复默认地址')
}

async function onLogin() {
  await formRef.value.validate()
  try {
    loading.value = true
    await user.loginAction({ username: form.username, password: form.password })
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-container {
  display: flex; height: 100vh; background: linear-gradient(135deg, #1e6091 0%, #2980b9 100%);
  position: relative;
  .login-box {
    position: relative; z-index: 2;
    background: #fff; width: 460px; margin: auto; padding: 32px 40px 24px;
    border-radius: 8px; box-shadow: 0 12px 32px rgba(0,0,0,0.18);
    .login-header { text-align: center; margin-bottom: 24px;
      h1 { margin: 0 0 4px; }
      p { color: #999; font-size: 12px; margin: 0; }
    }
    .login-tips { margin-top: 12px; text-align: center; color: #888; font-size: 12px; }
    .server-config {
      margin-top: 18px; padding-top: 14px;
      border-top: 1px dashed #e4e7ed;
      .toggle-row {
        display: flex; align-items: center; gap: 6px;
        cursor: pointer; user-select: none;
        color: #606266; font-size: 13px;
        padding: 4px 0;
        &:hover { color: #409eff; }
        .toggle-arrow { transition: transform .25s; margin-left: 2px; }
        .toggle-arrow.open { transform: rotate(180deg); }
        .current-server {
          margin-left: auto; color: #909399; font-size: 11px;
          max-width: 180px; overflow: hidden; text-overflow: ellipsis;
          white-space: nowrap; font-family: Consolas, monospace;
        }
      }
      .server-panel {
        padding: 12px 0 4px;
        .server-tip {
          color: #909399; font-size: 11px; margin-top: 6px;
          font-family: Consolas, monospace;
        }
      }
    }
  }
  .login-bg {
    position: absolute; inset: 0; opacity: 0.1;
    background-image: radial-gradient(circle at 20% 30%, #fff 1px, transparent 1px);
    background-size: 50px 50px;
  }
}
</style>
