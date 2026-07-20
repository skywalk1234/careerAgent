<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { UserFilled } from '@element-plus/icons-vue'
import FadeContent from '../components/FadeContent.vue'
import WAVES from 'vanta/dist/vanta.waves.min'
import * as THREE from 'three'
import { login, register } from '../services/auth'
import { isSuccessCode } from '../services/http'
import { setToken, setUser } from '../utils/auth'
import { useAppStore } from '../stores/app'
import githubIcon from '../assets/github.svg'
import giteeIcon from '../assets/gitee.svg'
import authIllustration from '../assets/auth.png'
import CircularText from "../components/CircularText.vue";
import DemoProjectNoticeDialog from '../components/DemoProjectNoticeDialog.vue'
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()

const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const demoNoticeVisible = ref(false)
const pendingRedirect = ref('')
const vantaRef = ref<HTMLElement | null>(null)
const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()
const flipStage = ref<'idle' | 'out' | 'in'>('idle')
const isSwitching = ref(false)
let vantaEffect: { destroy: () => void; resize?: () => void } | null = null

const loginForm = reactive({
  account: String(import.meta.env.VITE_DEMO_LOGIN_ACCOUNT || '12345678910'),
  password: String(import.meta.env.VITE_DEMO_LOGIN_PASSWORD || '123456'),
  autoLogin: true,
})

const registerForm = reactive({
  username: '',
  userphone: '',
  password: '',
  confirmPassword: '',
})

const loginRules: FormRules = {
  account: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '请输入11位手机号', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于6位', trigger: 'blur' },
  ],
}

const registerRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  userphone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '请输入11位手机号', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于6位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_, value, callback) => {
        if (value !== registerForm.password) {
          callback(new Error('两次输入密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

const authTitle = computed(() => (mode.value === 'login' ? '欢迎登录' : '创建账号'))
const authSubtitle = computed(() =>
  mode.value === 'login' ? '登录后继续使用职业规划智能体，支持第三方开源平台登录' : '注册后即可开始职业规划之旅',
)

const cardClass = computed(() => {
  if (flipStage.value === 'out') return 'card-flip-out'
  if (flipStage.value === 'in') return 'card-flip-in'
  return ''
})

const redirectPath = computed(() => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' ? redirect : '/'
})

function switchMode(next: 'login' | 'register') {
  if (mode.value === next || isSwitching.value) return
  isSwitching.value = true
  flipStage.value = 'out'

  window.setTimeout(() => {
    mode.value = next
    flipStage.value = 'in'

    window.setTimeout(() => {
      flipStage.value = 'idle'
      isSwitching.value = false
    }, 260)
  }, 240)
}

function noopThirdPartyLogin(provider: 'github' | 'gitee') {
  void provider
  ElMessageBox.alert(
  '当前为云端演示，仅提供演示账号登录与注册流程，不使用第三方平台账号。用户使用演示账号登录后，可在能力评估界面使用第三方开源平台只读模式授权，补强画像可信度',
  '第三方授权提示', 
  {
    type: 'warning',
    confirmButtonText: '确认',
  }
)
  // ElMessage.error('当前为云端演示，仅提供演示账号登录与注册流程，不使用第三方平台账号。用户使用演示账号登录后，可在能力评估界面使用第三方开源平台只读模式授权，补强画像可信度')
}

function goAdminConsole() {
  router.push('/admin')
}

async function submitLogin() {
  if (!loginFormRef.value) return
  await loginFormRef.value.validate(async (valid) => {
    if (!valid) return

    loading.value = true
    try {
      const response = await login({
        account: loginForm.account,
        password: loginForm.password,
        autoLogin: loginForm.autoLogin,
      })

      if (isSuccessCode(Number(response.data?.code))) {
        const token = response.data.payload?.token || response.data.data?.token
        if (!token) {
          ElMessage.error('登录响应缺少token')
          return
        }

        setToken(token)
        const userInfo = response.data.payload?.userInfo || response.data.data?.userInfo || {}
        setUser(userInfo)
        await appStore.ensureProfileSnapshot(true)
        ElMessage.success('登录成功')
        pendingRedirect.value = redirectPath.value
        demoNoticeVisible.value = true
      } else {
        ElMessage.error(response.data?.msg || '登录失败，请检查账号密码')
      }
    } catch {
      ElMessage.error('登录失败，请稍后再试')
    } finally {
      loading.value = false
    }
  })
}

function handleDemoNoticeConfirm() {
  router.replace(pendingRedirect.value || '/')
}

async function submitRegister() {
  if (!registerFormRef.value) return
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return

    loading.value = true
    try {
      const response = await register({
        username: registerForm.username,
        userphone: registerForm.userphone,
        password: registerForm.password,
      })

      if (isSuccessCode(Number(response.data?.code))) {
        ElMessage.success('注册成功，请登录')
        loginForm.account = registerForm.userphone
        loginForm.password = registerForm.password
        switchMode('login')
      } else {
        ElMessage.error(response.data?.msg || '注册失败，请稍后重试')
      }
    } catch {
      ElMessage.error('注册失败，请稍后重试')
    } finally {
      loading.value = false
    }
  })
}

onMounted(async () => {
  await nextTick()

  if (!vantaRef.value) return

  vantaEffect?.destroy()
  vantaEffect = WAVES({
    el: vantaRef.value,
    THREE: THREE,
    minHeight: 200.0,
    minWidth: 200.0,
    color: 0x87CEEB,
    shininess: 12,
    waveHeight: 10,
    waveSpeed: 0.45,
    zoom: 1.05,
    backgroundColor: 0xFFFFFF,
    mouseControls: false,
    touchControls: false,
  })

  window.setTimeout(() => {
    vantaEffect?.resize?.()
  }, 0)
})

onBeforeUnmount(() => {
  vantaEffect?.destroy()
})
</script>

<template>
  <div class="relative min-h-screen overflow-hidden">
    <div ref="vantaRef" class="vanta-bg" />
    <div class="absolute inset-0 z-10 bg-slate-900/15" />

    <div class="relative z-20 mx-auto flex min-h-screen max-w-6xl items-center justify-center px-4 py-8 md:py-10">
      <FadeContent
        :blur="true"
        :duration="700"
        :delay="120"
        :threshold="0.1"
        :initial-opacity="0"
        easing="ease-out"
        class-name="w-full"
      >
        <CircularText
          text="CAREER * PLANNER * AGENT * "
          :spin-duration="30"
          on-hover="speedUp"
          class-name="text-blue-500 -my-28 -translate-x-1/2 whitespace-nowrap opacity-80 z-0"
          style="z-index: -10;"
        />
        <div class="mx-auto w-full max-w-xl z-10">
          <section class="mb-4 text-center text-white md:mb-6">
            <h1 class="text-xl font-bold md:text-3xl"  style="-webkit-text-stroke: 1px white; text-shadow: 0 0 15px rgba(0,0,0,0.5);">微光职引</h1>
            <p class="mt-1 text-[11px] md:text-xs font-semibold" style="text-shadow: 0 0 15px rgba(0,0,0,0.5);">大学生职业规划智能体</p>
          </section>

          <section
            class="auth-card rounded-2xl border border-white/20 bg-white/95 p-6 shadow-2xl backdrop-blur-xl md:p-8"
            :class="cardClass"
          >
            <div class="auth-card-bg" aria-hidden="true">
              <img :src="authIllustration" alt="" class="auth-card-watermark" />
            </div>

            <div class="auth-card-content">
              <div class="mb-6">
                <h2 class="text-2xl font-semibold text-slate-900">{{ authTitle }}</h2>
                <p class="mt-2 text-sm text-slate-500">{{ authSubtitle }}</p>
              </div>

              <Transition name="auth-switch" mode="out-in">
                <div :key="mode" class="auth-panel">
                  <el-form
                    v-if="mode === 'login'"
                    ref="loginFormRef"
                    :model="loginForm"
                    :rules="loginRules"
                    class="auth-form"
                    label-position="top"
                    @submit.prevent="submitLogin"
                  >
                    <el-form-item label="手机号" prop="account">
                      <el-input v-model="loginForm.account" placeholder="请输入手机号" />
                    </el-form-item>

                    <el-form-item label="密码" prop="password">
                      <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入密码" />
                    </el-form-item>

                    <div class="mb-4 flex items-center justify-between">
                      <el-checkbox v-model="loginForm.autoLogin">7天自动登录</el-checkbox>
                    </div>

                    <el-button type="primary" size="large" class="auth-submit-btn" :loading="loading" @click="submitLogin">
                      立即登录
                    </el-button>

                    <div class="third-login-wrap">
                      <div class="third-login-divider">
                        <span>第三方登录</span>
                      </div>
                      <div class="third-login-list">
                        <el-tooltip content="使用 GitHub 登录" placement="top">
                          <button
                            type="button"
                            class="third-login-btn"
                            aria-label="使用 GitHub 登录"
                            @click="noopThirdPartyLogin('github')"
                          >
                            <img :src="githubIcon" alt="GitHub" class="third-login-icon" />
                          </button>
                        </el-tooltip>

                        <el-tooltip content="使用 Gitee 登录" placement="top">
                          <button
                            type="button"
                            class="third-login-btn"
                            aria-label="使用 Gitee 登录"
                            @click="noopThirdPartyLogin('gitee')"
                          >
                            <img :src="giteeIcon" alt="Gitee" class="third-login-icon" />
                          </button>
                        </el-tooltip>

                        <el-tooltip content="使用管理员账号登录" placement="top">
                          <button
                            type="button"
                            class="third-login-btn"
                            aria-label="使用管理员账号登录"
                            @click="goAdminConsole"
                          >
                            <el-icon class="third-login-ep-icon"><UserFilled /></el-icon>
                          </button>
                        </el-tooltip>
                      </div>
                    </div>

                    <div class="mt-4 text-center text-sm text-slate-500">
                      没有账号？
                      <button class="switch-link" type="button" @click="switchMode('register')">点击注册</button>
                    </div>
                  </el-form>

                  <el-form
                    v-else
                    ref="registerFormRef"
                    :model="registerForm"
                    :rules="registerRules"
                    class="auth-form"
                    label-position="top"
                    @submit.prevent="submitRegister"
                  >
                    <el-form-item label="用户名" prop="username">
                      <el-input v-model="registerForm.username" placeholder="请输入用户名" />
                    </el-form-item>

                    <el-form-item label="手机号" prop="userphone">
                      <el-input v-model="registerForm.userphone" placeholder="请输入手机号" />
                    </el-form-item>

                    <el-form-item label="密码" prop="password">
                      <el-input v-model="registerForm.password" type="password" show-password placeholder="请输入密码" />
                    </el-form-item>

                    <el-form-item label="确认密码" prop="confirmPassword">
                      <el-input
                        v-model="registerForm.confirmPassword"
                        type="password"
                        show-password
                        placeholder="请再次输入密码"
                      />
                    </el-form-item>

                    <el-button type="primary" size="large" class="auth-submit-btn" :loading="loading" @click="submitRegister">
                      注册并继续
                    </el-button>

                    <div class="mt-4 text-center text-sm text-slate-500">
                      已有账号？
                      <button class="switch-link" type="button" @click="switchMode('login')">点击登录</button>
                    </div>
                  </el-form>
                </div>
              </Transition>
            </div>
          </section>
        </div>
      </FadeContent>
    </div>

    <DemoProjectNoticeDialog v-model="demoNoticeVisible" @confirm="handleDemoNoticeConfirm" />
  </div>
</template>

<style scoped>
.auth-switch-enter-active,
.auth-switch-leave-active {
  transition: all 0.3s ease;
}

.auth-switch-enter-from {
  opacity: 0;
  transform: translateX(14px);
}

.auth-switch-leave-to {
  opacity: 0;
  transform: translateX(-14px);
}

.card-flip-out {
  animation: flipOut 0.24s ease-in forwards;
  transform-origin: center center;
}

.card-flip-in {
  animation: flipIn 0.26s ease-out forwards;
  transform-origin: center center;
}

@keyframes flipOut {
  from {
    opacity: 1;
    transform: perspective(900px) rotateY(0deg) scale(1);
  }

  to {
    opacity: 0.28;
    transform: perspective(900px) rotateY(90deg) scale(0.98);
  }
}

@keyframes flipIn {
  from {
    opacity: 0.28;
    transform: perspective(900px) rotateY(-90deg) scale(0.98);
  }

  to {
    opacity: 1;
    transform: perspective(900px) rotateY(0deg) scale(1);
  }
}

.switch-link {
  margin-left: 4px;
  border: none;
  background: transparent;
  padding: 0;
  color: #2563eb;
  text-decoration: underline;
  cursor: pointer;
}

.switch-link:hover {
  color: #1d4ed8;
}

.auth-card {
  position: relative;
  overflow: hidden;
  isolation: isolate;
  min-height: 540px;
}

.auth-card-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
}

.auth-card-bg::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 76% 74%, rgba(14, 164, 233, 0.052) 0%, rgba(14, 165, 233, 0) 52%),
    radial-gradient(circle at 16% 14%, rgba(59, 130, 246, 0.02) 0%, rgba(59, 130, 246, 0) 44%);
}

.auth-card-watermark {
  position: absolute;
  right: -34px;
  bottom: -76px;
  width: 268px;
  max-width: 52%;
  opacity: 0.26;
  transform: rotate(-8deg);
  filter: drop-shadow(0 18px 28px rgba(2, 132, 199, 0.22));
}

.auth-card-content {
  position: relative;
  z-index: 1;
}

.auth-panel {
  min-height: 390px;
}

.auth-form {
  display: flex;
  min-height: 390px;
  flex-direction: column;
}

.auth-submit-btn {
  align-self: center;
  min-width: 196px;
  border-radius: 9999px;
  padding-inline: 30px;
  font-weight: 600;
  letter-spacing: 0.02em;
  transition: transform 0.2s ease, box-shadow 0.2s ease, filter 0.2s ease, background 0.2s ease;
}

:deep(.auth-submit-btn.el-button--primary) {
  --el-button-bg-color: #4f93ff;
  --el-button-border-color: #4f93ff;
  --el-button-hover-bg-color: #62a0ff;
  --el-button-hover-border-color: #62a0ff;
  --el-button-active-bg-color: #2f7ef7;
  --el-button-active-border-color: #2f7ef7;
  border: none !important;
  background: linear-gradient(135deg, #4f93ff 0%, #35b8ff 100%) !important;
  box-shadow: 0 10px 24px rgba(59, 130, 246, 0.28);
}

:deep(.auth-submit-btn.el-button--primary:hover),
:deep(.auth-submit-btn.el-button--primary:focus-visible) {
  box-shadow: 0 14px 28px rgba(59, 130, 246, 0.32);
  filter: brightness(1.04) saturate(1.04);
  transform: translateY(-1px);
}

:deep(.auth-submit-btn.el-button--primary:active),
:deep(.auth-submit-btn.el-button--primary.is-active) {
  background: linear-gradient(135deg, #2f7ef7 0%, #1399df 100%) !important;
  border-color: #2f7ef7 !important;
  transform: translateY(0);
  box-shadow: 0 8px 16px rgba(37, 99, 235, 0.24), inset 0 1px 0 rgba(255, 255, 255, 0.14);
  filter: brightness(0.96);
}

.third-login-wrap {
  margin-top: 14px;
  
}

.third-login-divider {
  position: relative;
  margin-bottom: 10px;
  text-align: center;
  font-size: 12px;
  color: #94a3b8;
}

.third-login-divider::before {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: 50%;
  border-top: 1px solid #e2e8f0;
}

.third-login-divider span {
  position: relative;
  padding: 0 10px;
  background: transparent;
  text-shadow: 0 1px 2px rgba(15, 23, 42, 0.12);
}

.third-login-list {
  display: flex;
  justify-content: center;
  gap: 14px;
}

.third-login-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 9999px;
  border: 1px solid #dbe3ee;
  background: #ffffff;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}

.third-login-btn:hover {
  border-color: #bfdbfe;
  box-shadow: 0 8px 20px rgba(37, 99, 235, 0.12);
  transform: translateY(-1px);
}

.third-login-btn:active {
  transform: scale(0.94);
}

.third-login-icon {
  width: 18px;
  height: 18px;
}

.third-login-ep-icon {
  font-size: 18px;
}

.vanta-bg {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  z-index: 0;
  pointer-events: none;
}

@media (max-width: 768px) {
  .auth-card {
    min-height: 520px;
  }

  .auth-card-watermark {
    right: -24px;
    bottom: -52px;
    width: 220px;
    opacity: 0.13;
  }

  .auth-panel,
  .auth-form {
    min-height: 372px;
  }

  .auth-submit-btn {
    width: 100%;
    min-width: 0;
    border-radius: 12px;
    padding-inline: 14px;
  }
}
</style>
