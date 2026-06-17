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
      <div class="login-tips">
        <span>默认账号: <b>admin</b> / 密码: <b>admin123</b></span>
      </div>
    </div>
    <div class="login-bg"></div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const user = useUserStore()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: 'admin',
  password: 'admin123',
  remember: true
})

const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
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
    background: #fff; width: 420px; margin: auto; padding: 36px 40px;
    border-radius: 8px; box-shadow: 0 12px 32px rgba(0,0,0,0.18);
    .login-header { text-align: center; margin-bottom: 24px;
      h1 { margin: 0 0 4px; }
      p { color: #999; font-size: 12px; margin: 0; }
    }
    .login-tips { margin-top: 16px; text-align: center; color: #888; font-size: 12px; }
  }
  .login-bg {
    position: absolute; inset: 0; opacity: 0.1;
    background-image: radial-gradient(circle at 20% 30%, #fff 1px, transparent 1px);
    background-size: 50px 50px;
  }
}
</style>
