import { createRouter, createWebHistory } from 'vue-router'
import { getToken, isTokenExpired } from '../utils/auth'

const loadAuthView = () => import('../views/AuthView.vue')
const loadAdminConsoleView = () => import('../views/AdminConsoleView.vue')
const loadAdminOverviewPage = () => import('../views/admin/AdminOverviewPage.vue')
const loadAdminGovernancePage = () => import('../views/admin/AdminGovernancePage.vue')
const loadAdminJobsPage = () => import('../views/admin/AdminJobsPage.vue')
const loadHomeView = () => import('../views/HomeView.vue')
const loadJobGraphView = () => import('../views/JobGraphView.vue')
const loadStudentProfileView = () => import('../views/StudentProfileView.vue')
const loadMatchAnalysisView = () => import('../views/MatchAnalysisView.vue')
const loadCareerReportView = () => import('../views/CareerReportView.vue')

const routePrefetchLoaders: Record<string, () => Promise<unknown>> = {
  '/': loadHomeView,
  '/jobs': loadJobGraphView,
  '/student': loadStudentProfileView,
  '/match': loadMatchAnalysisView,
  '/report': loadCareerReportView,
}

let hasScheduledRoutePrefetch = false

function shouldSkipRoutePrefetch() {
  if (typeof window === 'undefined') return true
  const nav = window.navigator as Navigator & {
    connection?: {
      saveData?: boolean
      effectiveType?: string
    }
  }
  const connection = nav.connection
  if (!connection) return false
  if (connection.saveData) return true
  return String(connection.effectiveType || '').toLowerCase().includes('2g')
}

function scheduleRoutePrefetch(currentPath: string) {
  if (hasScheduledRoutePrefetch || shouldSkipRoutePrefetch()) return
  hasScheduledRoutePrefetch = true

  const preload = () => {
    const tasks = Object.entries(routePrefetchLoaders)
      .filter(([path]) => path !== currentPath)
      .map(([, loader]) => loader)

    const run = (index: number) => {
      if (index >= tasks.length) return
      tasks[index]()
        .catch(() => null)
        .finally(() => {
          globalThis.setTimeout(() => run(index + 1), 80)
        })
    }

    run(0)
  }

  if (typeof window !== 'undefined' && 'requestIdleCallback' in window) {
    ;(window as Window & { requestIdleCallback: (callback: IdleRequestCallback, options?: IdleRequestOptions) => number })
      .requestIdleCallback(() => preload(), { timeout: 1600 })
    return
  }

  globalThis.setTimeout(preload, 600)
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/auth',
      name: 'auth',
      component: loadAuthView,
      meta: {
        title: '登录/注册',
        guestOnly: true,
        hideLayout: true,
        transition: 'page-fade',
      },
    },
    {
      path: '/admin',
      name: 'admin',
      component: loadAdminConsoleView,
      meta: {
        title: '管理端',
        hideLayout: true,
        transition: 'page-fade',
      },
      children: [
        {
          path: '',
          redirect: '/admin/overview',
        },
        {
          path: 'overview',
          name: 'admin-overview',
          component: loadAdminOverviewPage,
          meta: {
            title: '管理总览',
            transition: 'page-fade',
          },
        },
        {
          path: 'governance',
          name: 'admin-governance',
          component: loadAdminGovernancePage,
          meta: {
            title: '岗位治理',
            transition: 'page-fade',
          },
        },
        {
          path: 'jobs',
          name: 'admin-jobs',
          component: loadAdminJobsPage,
          meta: {
            title: '岗位数据',
            transition: 'page-fade',
          },
        },
      ],
    },
    {
      path: '/',
      name: 'home',
      component: loadHomeView,
      meta: {
        title: '首页',
        requiresAuth: true,
        transition: 'page-slide',
      },
    },
    {
      path: '/jobs',
      name: 'jobs',
      component: loadJobGraphView,
      meta: {
        title: '岗位探索',
        requiresAuth: true,
        transition: 'page-slide',
      },
    },
    {
      path: '/student',
      name: 'student',
      component: loadStudentProfileView,
      meta: {
        title: '能力评估',
        requiresAuth: true,
        transition: 'page-slide',
      },
    },
    {
      path: '/match',
      name: 'match',
      component: loadMatchAnalysisView,
      meta: {
        title: '职业规划',
        requiresAuth: true,
        transition: 'page-slide',
      },
    },
    {
      path: '/report',
      name: 'report',
      component: loadCareerReportView,
      meta: {
        title: '生涯报告',
        requiresAuth: true,
        transition: 'page-slide',
      },
    },
  ],
})

router.beforeEach((to) => {
  const token = getToken()
  const authed = token ? !isTokenExpired(token) : false

  if (to.meta.requiresAuth && !authed) {
    return {
      path: '/auth',
      query: { redirect: to.fullPath },
    }
  }

  if (to.meta.guestOnly && authed) {
    return typeof to.query.redirect === 'string' ? to.query.redirect : '/'
  }

  return true
})

router.afterEach((to) => {
  const pageTitle = (to.meta.title as string | undefined) ?? '微光职引'
  document.title = `${pageTitle} - 微光职引智能体`

  if (to.meta.requiresAuth) {
    scheduleRoutePrefetch(to.path)
  }
})

export default router
