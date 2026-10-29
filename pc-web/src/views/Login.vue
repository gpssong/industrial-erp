<template>
  <div class="login-page">
    <!-- 左: 工厂视觉 + slogan (Figma .login-visual) -->
    <section class="login-visual">
      <img
        src="https://images.unsplash.com/photo-1770119074920-3c5603188095?crop=entropy&cs=tinysrgb&fit=crop&fm=jpg&q=80&w=1200"
        alt="现代化生产车间" />
      <div class="login-overlay">
        <div class="login-brand">
          <span class="brand-symbol"><i/><i/><i/></span>
          <span>{{ systemName }}</span>
        </div>
        <div>
          <div class="login-kicker">ERP + MES 一体化管理平台</div>
          <div class="login-slogan">让每一批原料可追溯，<br/>让每一道工序更透明。</div>
          <p>连接生产、仓储、设备与安全，驱动工厂数字化运营。</p>
        </div>
        <div class="visual-foot">HUASU · SMART FACTORY</div>
      </div>
    </section>

    <!-- 右: 登录卡 (Figma .login-form) -->
    <section class="login-form">
      <div class="login-card">
        <div class="mobile-brand"><span class="brand-symbol sm"><i/><i/><i/></span> {{ systemName }}</div>
        <div class="login-title">欢迎回来</div>
        <p class="login-sub">请登录您的工厂管理账号</p>

        <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="onLogin" label-position="top" size="large">
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
          <el-form-item label="验证码" prop="captchaCode">
            <div class="captcha-row">
              <el-input v-model="form.captchaCode" placeholder="请输入验证码" maxlength="4"
                        @keyup.enter="onLogin" />
              <img v-if="captchaImage" :src="captchaImage" class="captcha-img" alt="验证码"
                   title="点击刷新" @click="loadCaptcha" />
              <span v-else class="captcha-img captcha-loading" @click="loadCaptcha">加载中</span>
            </div>
          </el-form-item>
          <div class="login-options">
            <el-checkbox v-model="form.remember">记住我</el-checkbox>
          </div>
          <el-button type="primary" native-type="button" @click="onLogin" :loading="loading" class="login-btn">
            登录系统<el-icon style="margin-left:6px"><ArrowRight /></el-icon>
          </el-button>
        </el-form>

        <!-- 服务器连接设置 (折叠面板, 保留) -->
        <div class="server-config">
          <div class="toggle-row" @click="serverPanelOpen = !serverPanelOpen">
            <el-icon><Setting /></el-icon>
            <span>服务器连接设置</span>
            <el-icon class="toggle-arrow" :class="{ open: serverPanelOpen }"><ArrowDown /></el-icon>
            <span class="current-server">{{ currentServer }}</span>
          </div>
          <el-collapse-transition>
            <div v-show="serverPanelOpen" class="server-panel">
              <el-form label-width="70px" size="small">
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

      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessage, ElCollapseTransition } from 'element-plus'
import { User, Lock, Setting, ArrowDown, ArrowRight } from '@element-plus/icons-vue'
import { useSystemName } from '@/composables/useSystemName'
import { captcha } from '@/api/auth'

const router = useRouter()
const route = useRoute()
const user = useUserStore()
const formRef = ref()
const loading = ref(false)
const { systemName, loadSystemName } = useSystemName()

const form = reactive({
  username: '',
  password: '',
  captchaKey: '',
  captchaCode: '',
  remember: true
})

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
}

const captchaImage = ref('')

async function loadCaptcha() {
  try {
    const r = await captcha()
    const d = r?.data || r
    form.captchaKey = d?.captchaKey || ''
    captchaImage.value = d?.captchaImage || ''
    form.captchaCode = ''
  } catch (e) {
    captchaImage.value = ''
    form.captchaKey = ''
  }
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
    await user.loginAction({
      username: form.username,
      password: form.password,
      captchaKey: form.captchaKey,
      captchaCode: form.captchaCode
    })
    ElMessage.success('登录成功')
    // 登录成功后优先跳回原路径 (被 401 拦截时由 request.js 写入 ?redirect=)
    // 防御: redirect 指向登录页本身 (旧版本 401 拦截器会写 /login) 或站外地址时, 一律去工作台
    const redirect = route.query.redirect
    const target = typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('/login')
      ? redirect
      : '/dashboard'
    router.replace(target)
  } catch (e) {
    // P1-5: 生产环境不打印完整 AxiosError (含请求体 = 登录用户名密码)
    if (import.meta.env.DEV) console.error('[LOGIN_ERR]', e)
    // v1.1.31+: request.js 不再拦截器弹 ElMessage, 由组件 catch 统一弹
    // 优先用后端返回的 msg (e.g. "用户名或密码错误"), 兜底 "账号或密码错误, 请重试"
    const rawMsg = (e && (e.msg || e.message)) || ''
    const friendly = rawMsg || '账号或密码错误, 请重试'
    ElMessage.error(friendly)
    loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadSystemName()
  loadCaptcha()
})
</script>

<style scoped lang="scss">
// Figma 分屏登录 (.login-page)
.login-page {
  min-height: 100vh; display: grid; grid-template-columns: 1.15fr .85fr; background: #fff;
}
.login-visual {
  margin: 14px; position: relative; border-radius: 18px; overflow: hidden;
  > img { width: 100%; height: 100%; object-fit: cover; filter: saturate(.65); }
}
.login-overlay {
  position: absolute; inset: 0; padding: 40px 46px;
  display: flex; flex-direction: column; justify-content: space-between;
  color: #fff; background: linear-gradient(140deg, rgba(5, 30, 54, .91), rgba(9, 50, 89, .54));
}
.login-brand { display: flex; align-items: center; gap: 12px; font-size: 20px; font-weight: 750; }
.brand-symbol {
  display: inline-flex; width: 44px; height: 44px; padding: 9px; gap: 3px;
  align-items: flex-end; border-radius: 12px; background: var(--primary-color);
  i { flex: 1; background: #fff; border-radius: 2px 2px 0 0; transform: skewY(-18deg); }
  i:nth-child(1) { height: 50%; } i:nth-child(2) { height: 72%; } i:nth-child(3) { height: 94%; }
  &.sm { width: 34px; height: 34px; padding: 7px; }
}
.login-kicker { color: #8dc6ff; font-size: 12px; font-weight: 700; letter-spacing: .12em; }
.login-slogan {
  font-size: clamp(28px, 3vw, 48px); font-weight: 760; line-height: 1.28;
  margin-top: 17px; letter-spacing: -.025em;
}
.login-overlay p { color: #c4d3e1; line-height: 1.8; font-size: 13px; margin: 12px 0 0; }
.visual-foot { color: #7e9ab4; font-size: 10px; letter-spacing: .2em; }

.login-form { display: grid; place-items: center; padding: 40px; }
.login-card { width: min(400px, 100%); }
.mobile-brand {
  display: none; align-items: center; gap: 10px; color: var(--text); font-weight: 750; margin-bottom: 40px; font-size: 17px;
}
.login-title { font-size: 30px; font-weight: 780; color: var(--text); }
.login-sub { color: var(--muted); font-size: 13px; margin: 8px 0 26px; }

:deep(.el-form-item__label) { font-size: 12px; font-weight: 650; color: #4c5a6c; }
:deep(.el-input__wrapper) { min-height: 44px; border-radius: 8px; }
.captcha-row { display: flex; align-items: center; gap: 10px; width: 100%; }
.captcha-img {
  width: 108px; height: 40px; border-radius: 6px; cursor: pointer;
  border: 1px solid var(--line); object-fit: cover; flex: none;
}
.captcha-loading { display: grid; place-items: center; font-size: 12px; color: var(--muted); background: #f7f9fc; }
.login-options { display: flex; justify-content: flex-start; margin: 2px 0 18px; }
.login-btn {
  width: 100%; min-height: 48px; font-size: 14px; font-weight: 700;
  background: var(--primary-color); border: 0; border-radius: 9px;
  &:hover { background: var(--primary-dark); }
}

.server-config {
  margin-top: 20px; padding-top: 14px; border-top: 1px solid var(--line);
  .toggle-row {
    display: flex; align-items: center; gap: 6px; cursor: pointer; user-select: none;
    color: var(--muted); font-size: 13px; padding: 4px 0;
    &:hover { color: var(--primary-color); }
    .toggle-arrow { transition: transform .25s; margin-left: 2px; }
    .toggle-arrow.open { transform: rotate(180deg); }
    .current-server {
      margin-left: auto; color: #9aa6b4; font-size: 11px;
      max-width: 170px; overflow: hidden; text-overflow: ellipsis;
      white-space: nowrap; font-family: ui-monospace, monospace;
    }
  }
  .server-panel {
    padding: 12px 0 4px;
    .server-tip { color: #9aa6b4; font-size: 11px; margin-top: 6px; font-family: ui-monospace, monospace; }
  }
}

.login-tips {
  margin-top: 20px; display: flex; align-items: center; justify-content: center; gap: 7px;
  color: #8290a0; font-size: 12px;
  .el-icon { color: var(--success); }
}

// 移动端: 单列
@media (max-width: 860px) {
  .login-page { grid-template-columns: 1fr; background: linear-gradient(180deg, #f4f8fc, #fff); }
  .login-visual { display: none; }
  .login-form { padding: 40px 24px; }
  .mobile-brand { display: flex; }
  .login-title { font-size: 27px; }
}
</style>
