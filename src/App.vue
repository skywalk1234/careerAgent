<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { HomeFilled, Collection, UserFilled, DataAnalysis, Document, Menu, SwitchButton, ChatLineRound } from '@element-plus/icons-vue'
import GlobalAssistantWidget from './components/GlobalAssistantWidget.vue'
import TaskOrchestratorWidget from './components/TaskOrchestratorWidget.vue'
import { clearAuthStorage, getToken, isTokenExpired } from './utils/auth'
import { emitCareerReportRefresh } from './utils/globalAssistant'
import { getCareerReportPolishJobStatus } from './services/careerReport'
import { isSuccessCode } from './services/http'
import { useAppStore } from './stores/app'
import brandLogo from './assets/favicon.png'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const mobileOpen = ref(false)
const activeContainerPath = ref(route.path)
const pendingContainerPath = ref(route.path)
const pageTransitioning = ref(false)
const showLayout = computed(() => !route.meta.hideLayout)
let polishWatcherTimer: ReturnType<typeof setInterval> | null = null
const polishingPrompting = ref(false)

function getMainClassByPath(pathname: string) {
  if (pathname === '/') return 'px-0 py-0'
  return 'mx-auto max-w-7xl px-4 py-4 md:px-6 md:py-6'
}

const mainClass = computed(() => {
  if (!showLayout.value) return ''
  const stablePath = pageTransitioning.value ? activeContainerPath.value : pendingContainerPath.value
  return getMainClassByPath(stablePath)
})

const navItems = [
  { to: '/', label: '首页', icon: HomeFilled },
  { to: '/jobs', label: '岗位探索', icon: Collection },
  { to: '/student', label: '能力评估', icon: UserFilled },
  { to: '/match', label: '职业规划', icon: DataAnalysis },
  { to: '/report', label: '计划与行动方案', icon: Document },
  { to: '/interview', label: '模拟面试', icon: ChatLineRound },
]

watch(() => route.path, () => {
  mobileOpen.value = false
  pendingContainerPath.value = route.path
  if (!showLayout.value) {
    activeContainerPath.value = route.path
    pageTransitioning.value = false
  }
})

function handlePageBeforeLeave() {
  pageTransitioning.value = true
}

function handlePageAfterLeave() {
  activeContainerPath.value = pendingContainerPath.value
}

function handlePageAfterEnter() {
  activeContainerPath.value = pendingContainerPath.value
  pageTransitioning.value = false
}

// 点击空白处收起移动端菜单
function onDocumentClick(e: MouseEvent) {
  const target = e.target as Node
  const headerEl = document.querySelector('header')
  if (!headerEl) return
  if (!headerEl.contains(target)) {
    mobileOpen.value = false
  }
}
watch(mobileOpen, (v) => {
  if (v) document.addEventListener('click', onDocumentClick)
  else document.removeEventListener('click', onDocumentClick)
})

async function logout() {
  try {
    await ElMessageBox.confirm('当前为云端部署演示，退出登录后账号内容将清空。', '退出登录', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning',
      lockScroll: false,
    })

    clearAuthStorage()
    appStore.clearProfileSnapshot()
    ElMessage.success('已退出登录')
    router.replace('/auth')
  } catch {
    return
  }
}

async function preloadProfileSnapshot(force = false) {
  const token = getToken()
  const authed = token ? !isTokenExpired(token) : false
  if (!authed) return
  try {
    await appStore.ensureProfileSnapshot(force)
  } catch {
    // 画像预取失败时不阻塞页面
  }
}

async function checkPendingPolishJob() {
  if (polishingPrompting.value) return
  const raw = localStorage.getItem('career_report_pending_polish_job')
  if (!raw) return

  let pending: { polishJobId?: string; reportId?: string } | null = null
  try {
    pending = JSON.parse(raw) as { polishJobId?: string; reportId?: string }
  } catch {
    localStorage.removeItem('career_report_pending_polish_job')
    return
  }

  const polishJobId = String(pending?.polishJobId || '').trim()
  const reportId = String(pending?.reportId || '').trim()
  if (!polishJobId) {
    localStorage.removeItem('career_report_pending_polish_job')
    return
  }

  try {
    const response = await getCareerReportPolishJobStatus(polishJobId)
    const payload = response.data as {
      code: number
      msg: string
      data?: {
        status: 'processing' | 'succeeded' | 'failed'
      }
    }

    if (!isSuccessCode(Number(payload.code || 0)) || !payload.data) return
    if (payload.data.status === 'processing') return

    localStorage.removeItem('career_report_pending_polish_job')

    if (payload.data.status === 'failed') {
      ElMessage.warning('报告润色任务失败，请前往生涯报告页重试')
      return
    }

    polishingPrompting.value = true
    try {
      await ElMessageBox.confirm('润色完成，是否立即查看结果？', '智能润色完成', {
        type: 'success',
        confirmButtonText: '立即查看',
        cancelButtonText: '稍后查看',
      })
      if (route.path === '/report') {
        emitCareerReportRefresh({
          reportId: reportId || undefined,
          reason: 'polish-completed',
        })
      } else if (reportId) {
        await router.push(`/report?reportId=${encodeURIComponent(reportId)}`)
      } else {
        await router.push('/report')
      }
    } catch {
      // 用户稍后查看
    } finally {
      polishingPrompting.value = false
    }
  } catch {
    // 轮询异常时忽略，等待下次轮询
  }
}

onMounted(() => {
  preloadProfileSnapshot(false)
  polishWatcherTimer = setInterval(() => {
    checkPendingPolishJob()
  }, 2500)
})

watch(
  () => route.path,
  () => {
    if (showLayout.value) {
      preloadProfileSnapshot(false)
    }
  },
)

onBeforeUnmount(() => {
  if (polishWatcherTimer) {
    clearInterval(polishWatcherTimer)
    polishWatcherTimer = null
  }
})
</script>

<template>
  <div class="min-h-screen bg-slate-50 text-slate-800">
    <header v-if="showLayout" class="sticky top-0 z-40 border-b bg-white/95 backdrop-blur">
          <div class="relative flex h-14 items-center pl-1 pr-3 md:pl-2 md:pr-6">
            <RouterLink to="/" class="z-10 inline-flex items-center gap-2 text-sm font-semibold text-slate-900 md:text-base">
              <img :src="brandLogo" alt="微光职引" class="h-6 w-6 rounded ml-2" />
              <span>微光职引 · 大学生职业规划智能体</span>
            </RouterLink>

            <div class="pointer-events-none absolute left-1/2 top-1/2 hidden -translate-x-1/2 -translate-y-1/2 md:block">
              <nav class="pointer-events-auto flex items-center gap-6">
                <RouterLink
                  v-for="item in navItems"
                  :key="item.to"
                  :to="item.to"
                  :class="[{ 'text-blue-600 font-semibold': route.path === item.to }, 'text-sm text-slate-600 transition hover:text-blue-600 px-1 active:scale-95 active:translate-y-0.5']"
                >
                  <el-icon class="align-middle mr-1"><component :is="item.icon" /></el-icon>
                  <span class="align-middle">{{ item.label }}</span>
                </RouterLink>
              </nav>
            </div>

            <div class="ml-auto flex items-center gap-2 md:hidden">
              <button
                class="rounded-md border px-2 py-1 text-xs text-slate-600 flex items-center"
                @click="mobileOpen = !mobileOpen"
                aria-label="菜单"
              >
                <el-icon><Menu /></el-icon>
              </button>
              <button
                class="rounded-md border px-2 py-1 text-xs text-slate-600 flex items-center"
                @click="logout"
                aria-label="退出"
              >
                <el-icon><SwitchButton /></el-icon>
              </button>
            </div>

            <button error class="ml-auto hidden rounded-md border px-3 py-1 text-xs text-slate-600 md:flex items-center gap-2" @click="logout">
              <el-icon><SwitchButton /></el-icon>
            </button>
          </div>

      <Transition name="slide-down">
        <nav v-if="mobileOpen" class="border-t bg-white px-4 py-2 md:hidden">
          <div class="flex flex-col">
            <RouterLink
              v-for="item in navItems"
              :key="item.to"
              :to="item.to"
              :class="[{ 'text-blue-600 font-semibold': route.path === item.to }, 'flex items-center gap-3 rounded px-2 py-2 text-sm text-slate-700 hover:bg-slate-100 active:scale-95']"
              @click="mobileOpen = false"
            >
              <el-icon><component :is="item.icon" /></el-icon>
              <span class="ml-1">{{ item.label }}</span>
            </RouterLink>

            <button class="flex items-center gap-2 rounded px-2 py-2 text-sm text-slate-700 hover:bg-slate-100" @click="logout">
              <el-icon><SwitchButton /></el-icon>
              <span class="ml-1">退出</span>
            </button>
          </div>
        </nav>
      </Transition>
    </header>

    <main :class="mainClass">
      <RouterView v-slot="{ Component, route: currentRoute }">
        <component v-if="currentRoute.path.startsWith('/auth')" :is="Component" :key="currentRoute.fullPath" />
        <Transition
          v-else
          :name="(currentRoute.meta.transition as string) || 'page-fade'"
          mode="out-in"
          @before-leave="handlePageBeforeLeave"
          @after-leave="handlePageAfterLeave"
          @after-enter="handlePageAfterEnter"
        >
          <component :is="Component" :key="currentRoute.fullPath" />
        </Transition>
      </RouterView>
    </main>

    <GlobalAssistantWidget v-if="showLayout && route.path !== '/'" />
    <TaskOrchestratorWidget v-if="showLayout" />
  </div>
</template>

<style scoped>
.slide-down-enter-active, .slide-down-leave-active {
  transition: max-height 240ms ease, opacity 200ms ease;
  overflow: hidden;
}
.slide-down-enter-from, .slide-down-leave-to {
  max-height: 0;
  opacity: 0;
}
.slide-down-enter-to, .slide-down-leave-from {
  max-height: 400px;
  opacity: 1;
}

/* hover反馈 */
.active-press:active {
  transform: translateY(2px) scale(0.98);
}

</style>
