<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataAnalysis, Histogram, Setting, SwitchButton } from '@element-plus/icons-vue'
import brandLogo from '../assets/favicon.png'
import { clearAuthStorage } from '../utils/auth'
import { useAdminConsole } from '../composables/useAdminConsole'

const route = useRoute()
const router = useRouter()
const { initAdminConsole, disposeAdminConsole } = useAdminConsole()

const menuItems = [
  { path: '/admin/overview', label: '管理总览', icon: DataAnalysis },
  { path: '/admin/governance', label: '岗位治理', icon: Setting },
  { path: '/admin/jobs', label: '岗位数据', icon: Histogram },
]

const activeMenu = computed(() => {
  const hit = menuItems.find(item => route.path.startsWith(item.path))
  return hit?.path || '/admin/overview'
})

const pageTitleMap: Record<string, string> = {
  '/admin/overview': '管理总览',
  '/admin/governance': '岗位治理',
  '/admin/jobs': '岗位数据',
}

const currentPageTitle = computed(() => pageTitleMap[activeMenu.value] || '管理端')

async function logoutAdmin() {
  try {
    await ElMessageBox.confirm('确认退出管理端登录状态？', '退出登录', {
      type: 'warning',
      confirmButtonText: '确认退出',
      cancelButtonText: '取消',
    })

    clearAuthStorage()
    ElMessage.success('已退出登录')
    router.replace('/auth')
  } catch {
    return
  }
}

onMounted(async () => {
  await initAdminConsole()
})

onBeforeUnmount(() => {
  disposeAdminConsole()
})
</script>

<template>
  <div class="min-h-screen bg-slate-100">
    <div class="flex min-h-screen">
      <aside class="w-[236px] border-r border-slate-200 bg-white">
        <div class="border-b border-slate-200 px-4 py-4 h-16">
          <div class="flex items-center gap-2">
            <img :src="brandLogo" alt="微光职引" class="h-8 w-8 rounded" />
            <div>
              <p class="text-sm font-semibold text-slate-900">微光职引-管理端</p>
              <p class="text-xs text-slate-500">运营与治理中心</p>
            </div>
          </div>
        </div>

        <el-menu :default-active="activeMenu" router class="!border-r-0">
          <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </el-menu-item>
        </el-menu>
      </aside>

      <div class="flex min-h-screen flex-1 flex-col">
        <header class="flex h-16 items-center justify-between border-b border-slate-200 bg-white px-4 md:px-6">
          <div>
            <h1 class="text-base font-semibold text-slate-900">{{ currentPageTitle }}</h1>
          </div>

          <div class="flex items-center gap-2">
            <el-button size="small" type="danger" plain @click="logoutAdmin">
              <el-icon class="mr-1"><SwitchButton /></el-icon>
              退出登录
            </el-button>
          </div>
        </header>

        <main class="flex-1 overflow-auto p-4 md:p-5">
          <RouterView />
        </main>
      </div>
    </div>
  </div>
</template>
