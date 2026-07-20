<template>
  <div class="space-y-3">
    <div v-if="!summary.authorized" class="space-y-3 rounded-lg border border-dashed border-slate-300 bg-slate-50 p-4">
      <div>
        <h4 class="text-sm font-semibold text-slate-800">连接 GitHub 授权</h4>
        <p class="mt-1 text-xs text-slate-500">授权后可自动分析贡献热力、技术栈与项目活跃度，用于补充验证能力画像与提供加分建议。</p>
      </div>
      <ul class="list-disc space-y-1 pl-4 text-xs text-slate-600">
        <li>通过真实项目经验，减少主观填写偏差，补充项目与协作能力证据</li>
        <li>授权安全可靠：仅用于统计分析与加分，不影响基础评分</li>
        <li>可随时解绑/换绑，取消加分绑定。</li>
      </ul>
      <div class="flex flex-wrap gap-2">
        <el-button :loading="authLoading" type="primary" @click="startAuth('github')">
          <img :src="githubLogo" alt="GitHub" class="mr-1 h-4 w-4" />
          使用 GitHub 授权
        </el-button>
      </div>
    </div>

    <div v-else class="space-y-3 rounded-lg border border-emerald-200 bg-emerald-50/60 p-4">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div class="flex items-center gap-2 text-sm">
          <el-tag type="success">已授权 {{ summary.provider === 'github' ? 'GitHub' : 'Gitee' }}</el-tag>
          <span class="font-medium text-slate-700">{{ summary.provider === 'github' ? 'GitHub' : 'Gitee' }}用户：{{ summary.accountName }}</span>
          <a v-if="summary.profileUrl" :href="summary.profileUrl" target="_blank" class="text-xs text-blue-600 hover:underline">查看主页</a>
        </div>
        <el-button size="small" :loading="unbindLoading" @click="handleUnbind">解除授权</el-button>
      </div>

      <div class="space-y-3">
        <div class="rounded-md border border-slate-200 bg-white p-2">
          <p class="mb-1 text-xs text-slate-500">贡献热力图（最近一年）</p>
          <div ref="heatmapRef" class="h-[220px] w-full"></div>
        </div>
        <div class="rounded-md border border-slate-200 bg-white p-2">
          <p class="mb-1 text-xs text-slate-500">技术栈分布</p>
          <div ref="pieRef" class="h-[240px] w-full"></div>
        </div>
      </div>

      <div class="rounded-md border border-slate-200 bg-white p-3">
        <div class="mb-2 flex items-center justify-between">
          <p class="text-sm font-medium text-slate-700">项目经验加分</p>
        </div>
        <div v-if="summary.bonusDetails?.length" class="space-y-2">
          <div v-for="(item, index) in summary.bonusDetails" :key="`${item.dimension}-${index}`" class="rounded-md bg-slate-50 p-2 text-xs">
            <p class="font-medium text-slate-700">{{ getDimensionLabel(item.dimension) }}：+{{ item.delta }}</p>
            <p class="mt-1 text-slate-500">{{ item.reason }}</p>
          </div>
        </div>
        <el-empty v-else description="暂无加分明细" :image-size="50" />
      </div>
    </div>

    <el-dialog
      v-model="pendingDialogVisible"
      title="GitHub 授权已获取"
      width="520px"
      append-to-body
      :modal-append-to-body="true"
      :lock-scroll="false"
      :show-close="false"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      align-center
    >
      <div class="space-y-2 text-sm text-slate-700">
        <p>本次将读取并用于能力画像增强的数据包括：</p>
        <ul class="list-disc pl-5 text-slate-600">
          <li>GitHub 公开资料（用户名、主页链接）</li>
          <li>公开仓库统计（语言分布、仓库活跃度）</li>
          <li>贡献相关汇总（近一年热力数据）</li>
          <li>基于规则计算的加分明细（仅增强画像，不覆盖基础评分）</li>
        </ul>
        <p class="text-xs text-slate-500">你可以继续应用本次授权，或取消并清除授权结果。</p>
      </div>

      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button :disabled="pendingActionLoading" @click="handleCancelPending">取消</el-button>
          <el-button type="primary" :loading="pendingActionLoading" @click="handleConfirmPending">我已知晓，查看授权结果</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  cancelOpenSourcePending,
  confirmOpenSourcePending,
  getOpenSourceAuthUrl,
  getOpenSourcePending,
  getOpenSourceSummary,
  unbindOpenSource,
  type OpenSourcePendingResult,
  type OpenSourceProvider,
  type OpenSourceSummaryResult,
} from '../services/openSource'
import { isSuccessCode } from '../services/http'
import githubLogo from '../assets/github.svg'

const emit = defineEmits<{
  (event: 'authorized'): void
  (event: 'unbound'): void
}>()

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
  payload?: T
}

const dimensionLabelMap: Record<string, string> = {
  professionalSkill: '专业技能',
  certificate: '证书能力',
  innovation: '创新能力',
  internalMotivation: '内驱动力',
  learning: '学习能力',
  stressTolerance: '抗压能力',
  communication: '沟通能力',
  internship: '实习能力',
  language: '语言能力',
  leadership: '领导能力',
  adaptability: '适应能力',
  execution: '执行能力',
}

function getDimensionLabel(dimension: string) {
  return dimensionLabelMap[dimension] || dimension
}

const summary = ref<OpenSourceSummaryResult>({ authorized: false })
const authLoading = ref(false)
const unbindLoading = ref(false)
const pendingDialogVisible = ref(false)
const pendingActionLoading = ref(false)

const heatmapRef = ref<HTMLDivElement>()
const pieRef = ref<HTMLDivElement>()
let heatmapChart: echarts.ECharts | null = null
let pieChart: echarts.ECharts | null = null

function extractPayload<T>(response: { data: ApiResponse<T> }): T | undefined {
  return response.data.payload ?? response.data.data
}

function renderCharts() {
  if (!summary.value.authorized) return
  if (heatmapRef.value) {
    if (!heatmapChart) heatmapChart = echarts.init(heatmapRef.value)
    const points = summary.value.contributionHeatmap?.days ?? []
    const heatData = points.map((item) => [item.date, item.count])
    const range = points.length
      ? [points[points.length - 1].date, points[0].date]
      : [new Date().toISOString().slice(0, 10), new Date().toISOString().slice(0, 10)]
    heatmapChart.setOption({
      tooltip: {},
      visualMap: {
        min: 0,
        max: 15,
        show: false,
        inRange: { color: ['#ebedf0', '#9be9a8', '#40c463', '#30a14e', '#216e39'] },
      },
      calendar: {
        top: 28,
        left: 8,
        right: 8,
        bottom: 12,
        cellSize: ['auto', 12],
        range,
        yearLabel: { show: false },
        dayLabel: { firstDay: 1, nameMap: ['日', '一', '二', '三', '四', '五', '六'] },
        monthLabel: { nameMap: 'cn' },
      },
      series: [{
        type: 'heatmap',
        coordinateSystem: 'calendar',
        data: heatData,
      }],
    })
  }

  if (pieRef.value) {
    if (!pieChart) pieChart = echarts.init(pieRef.value)
    pieChart.setOption({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [{
        type: 'pie',
        radius: ['42%', '70%'],
        center: ['50%', '42%'],
        label: { formatter: '{b}: {d}%' },
        data: summary.value.languageStats ?? [],
      }],
    })
  }
}

async function loadSummary() {
  const response = await getOpenSourceSummary()
  const result = response.data as ApiResponse<OpenSourceSummaryResult>
  if (!isSuccessCode(Number(result.code))) return
  summary.value = extractPayload<OpenSourceSummaryResult>(response as { data: ApiResponse<OpenSourceSummaryResult> }) ?? { authorized: false }
  await nextTick()
  renderCharts()
}

function clearOAuthResultQueryFlags() {
  const url = new URL(window.location.href)
  if (!url.searchParams.has('openSourceAuth') && !url.searchParams.has('openSourceAuthMessage')) return
  url.searchParams.delete('openSourceAuth')
  url.searchParams.delete('openSourceAuthMessage')
  window.history.replaceState({}, '', url.toString())
}

async function checkPendingAuthorization() {
  const response = await getOpenSourcePending()
  const result = response.data as ApiResponse<OpenSourcePendingResult>
  if (!isSuccessCode(Number(result.code))) return
  const payload = extractPayload<OpenSourcePendingResult>(response as { data: ApiResponse<OpenSourcePendingResult> })
  if (!payload?.pending) return
  pendingDialogVisible.value = true
}

async function startAuth(provider: OpenSourceProvider) {
  authLoading.value = true
  try {
    const authResponse = await getOpenSourceAuthUrl(provider, window.location.href)
    const authResult = authResponse.data as ApiResponse<{ provider: OpenSourceProvider; state: string; authUrl: string }>
    if (!isSuccessCode(Number(authResult.code))) {
      ElMessage.error(authResult.msg || '获取授权地址失败')
      return
    }
    const payload = extractPayload<{ provider: OpenSourceProvider; state: string; authUrl: string }>(authResponse as { data: ApiResponse<{ provider: OpenSourceProvider; state: string; authUrl: string }> })
    if (!payload) return
    const authUrl = String(payload.authUrl || '').trim()
    if (!authUrl) {
      throw new Error('授权地址为空')
    }

    // 直接在当前页跳转，避免新窗口被浏览器拦截导致“点击无响应”。
    window.location.assign(authUrl)
    return
  } catch {
    ElMessage.error('授权失败，请稍后重试')
  } finally {
    authLoading.value = false
  }
}

async function handleUnbind() {
  unbindLoading.value = true
  try {
    const response = await unbindOpenSource()
    const result = response.data as ApiResponse<null>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '解绑失败')
      return
    }
    summary.value = { authorized: false }
    if (heatmapChart) {
      heatmapChart.dispose()
      heatmapChart = null
    }
    if (pieChart) {
      pieChart.dispose()
      pieChart = null
    }
    emit('unbound')
    ElMessage.success('已解除授权')
  } finally {
    unbindLoading.value = false
  }
}

async function handleConfirmPending() {
  pendingActionLoading.value = true
  authLoading.value = true
  try {
    const response = await confirmOpenSourcePending()
    const result = response.data as ApiResponse<null>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '确认授权失败')
      return
    }
    pendingDialogVisible.value = false
    await loadSummary()
    emit('authorized')
    ElMessage.success('授权信息已生效，正在展示加分结果')
  } finally {
    pendingActionLoading.value = false
    authLoading.value = false
  }
}

async function handleCancelPending() {
  pendingActionLoading.value = true
  try {
    const response = await cancelOpenSourcePending()
    const result = response.data as ApiResponse<null>
    if (!isSuccessCode(Number(result.code))) {
      ElMessage.error(result.msg || '取消授权失败')
      return
    }
    pendingDialogVisible.value = false
    ElMessage.info('已取消本次授权')
  } finally {
    pendingActionLoading.value = false
  }
}

onMounted(() => {
  clearOAuthResultQueryFlags()
  checkPendingAuthorization().catch(() => {
    pendingDialogVisible.value = false
  })
  loadSummary().catch(() => {
    summary.value = { authorized: false }
  })
  window.addEventListener('resize', handleResize)
})

function handleResize() {
  heatmapChart?.resize()
  pieChart?.resize()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  heatmapChart?.dispose()
  pieChart?.dispose()
  heatmapChart = null
  pieChart = null
})
</script>
