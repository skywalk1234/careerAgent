<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import { renderMarkdown } from '../utils/markdown'
import {
  deleteInterviewReport,
  getInterviewReportDetail,
  getInterviewReportList,
  type InterviewReportDetail,
  type InterviewReportListItem,
} from '../services/mockInterview'
import { isSuccessCode } from '../services/http'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
}

const router = useRouter()
const route = useRoute()

const initLoading = ref(false)
const detailLoading = ref(false)
const reportList = ref<InterviewReportListItem[]>([])
const currentReportId = ref<number | ''>('')
const currentReport = ref<InterviewReportDetail | null>(null)
const deletingId = ref<number | ''>('')

const currentReportHtml = computed(() => renderMarkdown(currentReport.value?.content || ''))

function formatDateTime(value?: string | null) {
  const text = String(value || '')
  if (!text) return ''
  return text.replace('T', ' ').slice(0, 16)
}

function pickTargetReportId(preferred?: string | number): number {
  const preferredText = String(preferred ?? '').trim()
  if (preferredText) {
    const byPreferred = reportList.value.find(item => String(item.id) === preferredText)
    if (byPreferred) return byPreferred.id
  }
  const first = reportList.value[0]
  return first ? first.id : 0
}

async function loadDetail(reportId: number) {
  detailLoading.value = true
  currentReport.value = null
  try {
    const response = await getInterviewReportDetail(reportId)
    const payload = response.data as ApiResponse<InterviewReportDetail>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '报告加载失败')
    }
    currentReport.value = payload.data
  } catch (error) {
    currentReportId.value = ''
    ElMessage.error(error instanceof Error ? error.message : '报告加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function openReport(reportId: number) {
  if (!reportList.value.some(item => item.id === reportId)) return
  if (currentReportId.value === reportId && currentReport.value) return
  currentReportId.value = reportId
  await loadDetail(reportId)
}

async function handleDeleteReport(item: InterviewReportListItem) {
  if (deletingId.value) return
  try {
    await ElMessageBox.confirm(`确定删除「${item.title}」吗？删除后不可恢复。`, '删除面试记录', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  deletingId.value = item.id
  try {
    const response = await deleteInterviewReport(item.id)
    const payload = response.data as ApiResponse<null>
    if (!isSuccessCode(payload.code)) {
      ElMessage.error(payload.msg || '删除失败')
      return
    }
    ElMessage.success('面试记录已删除')
    await refreshList()
    // 若删的是当前打开的那份，回落到最新一份
    if (currentReportId.value === item.id || !reportList.value.some(r => r.id === currentReportId.value)) {
      currentReport.value = null
      currentReportId.value = ''
      const target = pickTargetReportId()
      if (target) {
        currentReportId.value = target
        await loadDetail(target)
      }
    }
  } catch {
    ElMessage.error('删除失败，请稍后重试')
  } finally {
    deletingId.value = ''
  }
}

async function refreshList() {
  try {
    const response = await getInterviewReportList()
    const payload = response.data as ApiResponse<{ total: number; list: InterviewReportListItem[] }>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '面试记录加载失败')
    }
    reportList.value = payload.data.list || []
  } catch (error) {
    reportList.value = []
    ElMessage.error(error instanceof Error ? error.message : '面试记录加载失败')
  }
}

async function loadInitial() {
  initLoading.value = true
  try {
    await refreshList()
    const queryReportId = String(route.query.reportId || '').trim()
    const target = pickTargetReportId(queryReportId)
    if (target) {
      currentReportId.value = target
      await loadDetail(target)
    }
  } finally {
    initLoading.value = false
  }
}

watch(
  () => route.query.reportId,
  async (next) => {
    const reportIdText = String(next || '').trim()
    const reportId = Number(reportIdText)
    if (!Number.isFinite(reportId)) return
    if (!reportList.value.some(item => item.id === reportId)) return
    if (currentReportId.value === reportId && currentReport.value) return
    currentReportId.value = reportId
    await loadDetail(reportId)
  },
)

onMounted(loadInitial)
</script>

<template>
  <section class="space-y-4 md:space-y-6" v-loading="initLoading">
    <div class="flex flex-col gap-1 rounded-xl border border-slate-200 bg-white p-4 md:flex-row md:items-center md:justify-between md:p-5">
      <div class="space-y-1">
        <h2 class="text-xl font-semibold text-slate-900 md:text-2xl">面试记录</h2>
        <p class="text-sm text-slate-500">每场模拟面试结束后，AI 面试官自动生成的总结报告；可回看每道问题的表现与补强建议。</p>
      </div>
      <el-button plain @click="router.push('/interview')">去开始一场模拟面试</el-button>
    </div>

    <div class="grid grid-cols-1 gap-4 xl:grid-cols-[320px_1fr]">
      <el-card shadow="never" class="sticky top-[72px] h-fit self-start border border-slate-200 left-sticky-panel">
        <template #header>
          <div class="flex items-center justify-between">
            <span class="font-medium">报告列表</span>
            <span class="text-xs text-slate-500">共 {{ reportList.length }} 份</span>
          </div>
        </template>

        <div v-if="reportList.length" class="space-y-2">
          <div
            v-for="item in reportList"
            :key="item.id"
            class="group flex items-center gap-1 rounded-lg border py-1 pl-1 pr-1 transition"
            :class="currentReportId === item.id ? 'border-blue-400 bg-blue-50' : 'border-slate-200 bg-white hover:border-blue-300 hover:bg-blue-50'"
          >
            <button
              type="button"
              class="min-w-0 flex-1 rounded-md px-2 py-1 text-left"
              @click="openReport(item.id)"
            >
              <p class="line-clamp-1 text-sm font-medium text-slate-800">{{ item.title }}</p>
              <p class="mt-1 truncate text-xs text-slate-500">
                <template v-if="item.companyName || item.jobName">
                  {{ [item.companyName, item.jobName].filter(Boolean).join(' · ') }} · {{ formatDateTime(item.createdAt) }}
                </template>
                <template v-else>{{ formatDateTime(item.createdAt) }} 面试总结</template>
              </p>
            </button>
            <el-button
              class="shrink-0"
              size="small"
              text
              type="danger"
              :icon="Delete"
              :loading="deletingId === item.id"
              aria-label="删除该面试记录"
              @click.stop="handleDeleteReport(item)"
            />
          </div>
        </div>

        <div v-else class="rounded-lg border border-dashed border-slate-200 p-4 text-center text-sm text-slate-500">
          暂无面试记录。完成一场模拟面试后，AI 面试官会自动为你生成总结报告。
        </div>
      </el-card>

      <div class="min-w-0 space-y-4">
        <el-card v-if="!reportList.length" shadow="never" class="border border-dashed border-slate-300">
          <el-empty description="还没有面试报告，去和 AI 面试官来一场模拟面试吧">
            <el-button type="primary" @click="router.push('/interview')">开始模拟面试</el-button>
          </el-empty>
        </el-card>

        <el-card v-else-if="!currentReport" shadow="never" class="border border-dashed border-slate-300">
          <div class="py-12 text-center text-sm text-slate-500">
            <span v-if="detailLoading">正在加载报告全文…</span>
            <span v-else>请从左侧选择一份报告查看全文。</span>
          </div>
        </el-card>

        <el-card v-else shadow="never" class="border border-slate-200">
          <template #header>
            <div class="space-y-2">
              <div class="flex items-center justify-between gap-3">
                <div class="flex min-w-0 items-center gap-2">
                  <el-tag size="small" type="primary" effect="light">面试总结</el-tag>
                  <h3 class="min-w-0 truncate text-base font-semibold text-slate-900">{{ currentReport.title }}</h3>
                </div>
                <span class="shrink-0 text-xs text-slate-500">{{ formatDateTime(currentReport.createdAt) }} 生成</span>
              </div>
              <div
                v-if="currentReport.jobName || currentReport.companyName || currentReport.jobId || currentReport.sessionId"
                class="flex flex-wrap items-center gap-2 border-t border-slate-100 pt-2 text-xs text-slate-500"
              >
                <template v-if="currentReport.companyName">
                  <span class="rounded-md bg-slate-100 px-2 py-0.5 text-slate-600">{{ currentReport.companyName }}</span>
                </template>
                <template v-if="currentReport.jobName">
                  <span class="rounded-md bg-slate-100 px-2 py-0.5 text-slate-600">{{ currentReport.jobName }}</span>
                </template>
                <template v-if="currentReport.jobId">
                  <span class="rounded-md bg-slate-100 px-2 py-0.5 text-slate-600">岗位 {{ currentReport.jobId }}</span>
                </template>
                <template v-if="currentReport.sessionId">
                  <span class="ml-auto text-slate-400">场次 {{ currentReport.sessionId.slice(0, 8) }}</span>
                </template>
              </div>
            </div>
          </template>

          <div class="report-markdown markdown-body" v-loading="detailLoading" v-html="currentReportHtml"></div>
        </el-card>
      </div>
    </div>

    <el-backtop :right="20" :bottom="28" :visibility-height="420" />
  </section>
</template>

<style scoped>
.left-sticky-panel {
  max-height: calc(100vh - 96px);
  overflow-y: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.left-sticky-panel::-webkit-scrollbar {
  display: none;
}

.report-markdown {
  min-height: 120px;
}
</style>
