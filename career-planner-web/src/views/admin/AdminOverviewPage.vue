<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { DataAnalysis, Refresh, UserFilled } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { useAdminConsole } from '../../composables/useAdminConsole'

const {
  refreshLoading,
  metricCards,
  completionRatio,
  jobCountSummary,
  operationLogs,
  publicOverview,
  profileAggregate,
  dataSourceHint,
  refreshDashboard,
  initAdminConsole,
} = useAdminConsole()

const trendEl = ref<HTMLDivElement | null>(null)
const radarEl = ref<HTMLDivElement | null>(null)
let trendChart: echarts.ECharts | null = null
let radarChart: echarts.ECharts | null = null

const latestLogs = computed(() => operationLogs.value.slice(0, 6))

function renderTrendChart() {
  if (!trendEl.value || !publicOverview.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendEl.value)
  }

  const source = publicOverview.value.charts.matchScoreTrend || []
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 30, right: 20, top: 26, bottom: 24 },
    xAxis: {
      type: 'category',
      data: source.map(item => item.date),
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      splitLine: { lineStyle: { type: 'dashed' } },
    },
    series: [
      {
        name: '匹配评分趋势',
        type: 'line',
        smooth: true,
        data: source.map(item => item.value),
        areaStyle: { opacity: 0.2 },
        symbolSize: 6,
      },
    ],
  })
}

function renderRadarChart() {
  if (!radarEl.value || !profileAggregate.value) return
  if (!radarChart) {
    radarChart = echarts.init(radarEl.value)
  }

  const scoreMap = profileAggregate.value.averageScores
  const indicators = [
    { key: 'professionalSkill', label: '专业技能' },
    { key: 'certificate', label: '证书能力' },
    { key: 'innovation', label: '创新能力' },
    { key: 'internalMotivation', label: '内驱动力' },
    { key: 'learning', label: '学习能力' },
    { key: 'stressTolerance', label: '抗压能力' },
    { key: 'communication', label: '沟通能力' },
    { key: 'internship', label: '实习能力' },
    { key: 'language', label: '语言能力' },
    { key: 'leadership', label: '领导能力' },
    { key: 'adaptability', label: '适应能力' },
    { key: 'execution', label: '执行能力' },
  ] as const

  radarChart.setOption({
    tooltip: { trigger: 'item' },
    radar: {
      radius: '64%',
      indicator: indicators.map(item => ({ name: item.label, max: 100 })),
    },
    series: [
      {
        type: 'radar',
        areaStyle: { opacity: 0.2 },
        data: [
          {
            name: '学生画像能力均值',
            value: indicators.map(item => scoreMap[item.key]),
          },
        ],
      },
    ],
  })
}

async function renderCharts() {
  await nextTick()
  renderTrendChart()
  renderRadarChart()
}

function resizeCharts() {
  trendChart?.resize()
  radarChart?.resize()
}

async function handleRefresh() {
  await refreshDashboard()
  await renderCharts()
  ElMessage.success('总览数据已更新')
}

watch(
  [publicOverview, profileAggregate],
  async () => {
    await renderCharts()
  },
  { deep: true },
)

onMounted(async () => {
  await initAdminConsole()
  await renderCharts()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  trendChart?.dispose()
  radarChart?.dispose()
})
</script>

<template>
  <div class="space-y-4">
    <section class="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm md:p-5">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 class="text-lg font-semibold text-slate-900">管理总览</h2>
          <p class="mt-1 text-sm text-slate-500">持续追踪岗位图谱运营状态与用户使用趋势</p>
        </div>

        <el-button type="primary" size="small" :loading="refreshLoading" @click="handleRefresh">
          <el-icon class="mr-1"><Refresh /></el-icon>
          刷新总览数据
        </el-button>
      </div>

      <div class="mt-4 grid gap-3 md:grid-cols-4">
        <div class="rounded-xl border border-slate-200 bg-slate-50 p-3">
          <p class="text-xs text-slate-500">岗位总量</p>
          <p class="mt-1 text-xl font-semibold text-slate-900">{{ jobCountSummary.total }}</p>
        </div>
        <div class="rounded-xl border border-slate-200 bg-slate-50 p-3">
          <p class="text-xs text-slate-500">有效岗位</p>
          <p class="mt-1 text-xl font-semibold text-emerald-600">{{ jobCountSummary.active }}</p>
        </div>
        <div class="rounded-xl border border-slate-200 bg-slate-50 p-3">
          <p class="text-xs text-slate-500">即将过期</p>
          <p class="mt-1 text-xl font-semibold text-amber-600">{{ jobCountSummary.expiring }}</p>
        </div>
        <div class="rounded-xl border border-slate-200 bg-slate-50 p-3">
          <p class="text-xs text-slate-500">任务成功率</p>
          <p class="mt-1 text-xl font-semibold text-blue-600">{{ completionRatio }}%</p>
        </div>
      </div>
    </section>

    <section class="grid gap-4 xl:grid-cols-12">
      <div class="space-y-4 xl:col-span-8">
        <el-card shadow="never" class="!rounded-2xl !border-slate-200">
          <template #header>
            <div class="flex items-center justify-between">
              <div class="flex items-center gap-2 text-slate-800">
                <el-icon><DataAnalysis /></el-icon>
                <span class="font-medium">用户关键指标</span>
              </div>
              <span class="text-xs text-slate-500">
                更新时间：{{ publicOverview?.updatedAt ? new Date(publicOverview.updatedAt).toLocaleString('zh-CN') : '--' }}
              </span>
            </div>
          </template>

          <div v-if="dataSourceHint" class="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700">
            {{ dataSourceHint }}
          </div>

          <div class="grid gap-2 md:grid-cols-2">
            <div
              v-for="metric in metricCards"
              :key="metric.key"
              class="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2"
            >
              <p class="text-xs text-slate-500">{{ metric.label }}</p>
              <p class="mt-1 text-lg font-semibold text-slate-900">{{ metric.value }} {{ metric.unit }}</p>
              <p class="text-xs" :class="metric.trend.direction === 'down' ? 'text-rose-600' : 'text-emerald-600'">
                {{ metric.trend.period }} {{ metric.trend.direction === 'down' ? '↓' : '↑' }} {{ metric.trend.delta }}%
              </p>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="!rounded-2xl !border-slate-200">
          <template #header>
            <div class="flex items-center gap-2 text-slate-800">
              <el-icon><DataAnalysis /></el-icon>
              <span class="font-medium">匹配评分趋势</span>
            </div>
          </template>
          <div ref="trendEl" class="h-[320px] w-full" />
        </el-card>
      </div>

      <div class="space-y-4 xl:col-span-4">
        <el-card shadow="never" class="!rounded-2xl !border-slate-200">
          <template #header>
            <div class="flex items-center gap-2 text-slate-800">
              <el-icon><UserFilled /></el-icon>
              <span class="font-medium">能力均值雷达</span>
            </div>
          </template>

          <div class="mb-2 grid grid-cols-2 gap-2 text-xs">
            <div class="rounded-lg border border-slate-200 bg-slate-50 p-2 text-slate-600">
              画像完整度均值：
              <span class="font-medium text-slate-900">{{ profileAggregate?.averageProgress.completenessScore ?? '--' }}</span>
            </div>
            <div class="rounded-lg border border-slate-200 bg-slate-50 p-2 text-slate-600">
              竞争力均值：
              <span class="font-medium text-slate-900">{{ profileAggregate?.averageProgress.competitivenessScore ?? '--' }}</span>
            </div>
          </div>
          <div ref="radarEl" class="h-[270px] w-full" />
        </el-card>

        <el-card shadow="never" class="!rounded-2xl !border-slate-200">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-medium text-slate-800">最近操作</span>
              <span class="text-xs text-slate-500">{{ latestLogs.length }} 条</span>
            </div>
          </template>

          <div class="space-y-2">
            <div
              v-for="log in latestLogs"
              :key="log.id"
              class="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2"
            >
              <div class="flex items-center justify-between text-xs text-slate-500">
                <span>{{ log.time }}</span>
                <el-tag :type="log.status === 'failed' ? 'danger' : log.status === 'running' ? 'warning' : 'success'" size="small">
                  {{ log.status === 'running' ? '进行中' : log.status === 'failed' ? '失败' : '成功' }}
                </el-tag>
              </div>
              <p class="mt-1 text-sm text-slate-700">{{ log.content }}</p>
            </div>
            <el-empty v-if="!latestLogs.length" description="暂无操作记录" :image-size="60" />
          </div>
        </el-card>
      </div>
    </section>
  </div>
</template>
