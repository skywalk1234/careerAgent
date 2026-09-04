<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import {
  getCareerPlanDetail,
  getCareerPlanList,
  type CareerPlanDetail,
  type CareerPlanListItem,
} from '../services/careerPlan'
import { isSuccessCode } from '../services/http'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
}

const router = useRouter()
const route = useRoute()

const markdownRenderer = new MarkdownIt({ html: false, linkify: true, breaks: true })
markdownRenderer.enable(['table', 'strikethrough'])
markdownRenderer.renderer.rules.table_open = () => '<div class="md-table-scroll"><table>'
markdownRenderer.renderer.rules.table_close = () => '</table></div>'

const initLoading = ref(false)
const detailLoading = ref(false)
const planList = ref<CareerPlanListItem[]>([])
const currentPlanId = ref<number | ''>('')
const currentPlan = ref<CareerPlanDetail | null>(null)

const currentPlanHtml = computed(() => {
  const content = String(currentPlan.value?.content || '').trim()
  if (!content) return ''
  return DOMPurify.sanitize(markdownRenderer.render(content))
})

function isActivePlan(item: { status?: string } | null | undefined) {
  return String(item?.status || '') === 'active'
}

function formatDateTime(value?: string | null) {
  const text = String(value || '')
  if (!text) return ''
  return text.replace('T', ' ').slice(0, 16)
}

function pickTargetPlanId(preferred?: string | number): number {
  const preferredText = String(preferred ?? '').trim()
  if (preferredText) {
    const byPreferred = planList.value.find(item => String(item.id) === preferredText)
    if (byPreferred) return byPreferred.id
  }
  const active = planList.value.find(item => isActivePlan(item))
  if (active) return active.id
  const first = planList.value[0]
  return first ? first.id : 0
}

async function loadDetail(planId: number) {
  detailLoading.value = true
  currentPlan.value = null
  try {
    const response = await getCareerPlanDetail(planId)
    const payload = response.data as ApiResponse<CareerPlanDetail>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '方案加载失败')
    }
    currentPlan.value = payload.data
  } catch (error) {
    currentPlanId.value = ''
    ElMessage.error(error instanceof Error ? error.message : '方案加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function openPlan(planId: number) {
  if (!planList.value.some(item => item.id === planId)) return
  if (currentPlanId.value === planId && currentPlan.value) return
  currentPlanId.value = planId
  await loadDetail(planId)
}

async function refreshList() {
  try {
    const response = await getCareerPlanList()
    const payload = response.data as ApiResponse<{ total: number; list: CareerPlanListItem[] }>
    if (!isSuccessCode(payload.code) || !payload.data) {
      throw new Error(payload.msg || '方案列表加载失败')
    }
    planList.value = payload.data.list || []
  } catch (error) {
    planList.value = []
    ElMessage.error(error instanceof Error ? error.message : '方案列表加载失败')
  }
}

async function loadInitial() {
  initLoading.value = true
  try {
    await refreshList()
    const queryPlanId = String(route.query.planId || '').trim()
    const target = pickTargetPlanId(queryPlanId)
    if (target) {
      currentPlanId.value = target
      await loadDetail(target)
    }
  } finally {
    initLoading.value = false
  }
}

watch(
  () => route.query.planId,
  async (next) => {
    const planIdText = String(next || '').trim()
    const planId = Number(planIdText)
    if (!Number.isFinite(planId)) return
    if (!planList.value.some(item => item.id === planId)) return
    if (currentPlanId.value === planId && currentPlan.value) return
    currentPlanId.value = planId
    await loadDetail(planId)
  },
)

onMounted(loadInitial)
</script>

<template>
  <section class="space-y-4 md:space-y-6" v-loading="initLoading">
    <div class="flex flex-col gap-1 rounded-xl border border-slate-200 bg-white p-4 md:flex-row md:items-center md:justify-between md:p-5">
      <div class="space-y-1">
        <h2 class="text-xl font-semibold text-slate-900 md:text-2xl">计划与行动方案</h2>
        <p class="text-sm text-slate-500">由 AI 职业规划专家生成的分阶段行动方案；再次规划会自动归档旧版，历史版本保留可回看成长轨迹。</p>
      </div>
      <el-button plain @click="router.push('/')">去首页让 AI 规划</el-button>
    </div>

    <div class="grid grid-cols-1 gap-4 xl:grid-cols-[320px_1fr]">
      <el-card shadow="never" class="sticky top-[72px] h-fit self-start border border-slate-200 left-sticky-panel">
        <template #header>
          <div class="flex items-center justify-between">
            <span class="font-medium">方案列表</span>
            <span class="text-xs text-slate-500">共 {{ planList.length }} 份</span>
          </div>
        </template>

        <div v-if="planList.length" class="space-y-2">
          <button
            v-for="item in planList"
            :key="item.id"
            type="button"
            class="w-full rounded-lg border px-3 py-2 text-left transition hover:border-blue-300 hover:bg-blue-50"
            :class="currentPlanId === item.id ? 'border-blue-400 bg-blue-50' : 'border-slate-200 bg-white'"
            @click="openPlan(item.id)"
          >
            <div class="flex items-center justify-between gap-2">
              <p class="line-clamp-1 text-sm font-medium text-slate-800">{{ item.title }}</p>
              <el-tag v-if="isActivePlan(item)" size="small" type="success" effect="light">当前</el-tag>
              <el-tag v-else size="small" type="info" effect="plain">历史</el-tag>
            </div>
            <p class="mt-1 text-xs text-slate-500">{{ formatDateTime(item.createdAt) }} 生成</p>
          </button>
        </div>

        <div v-else class="rounded-lg border border-dashed border-slate-200 p-4 text-center text-sm text-slate-500">
          暂无方案。到首页与 AI 职业规划助手对话，即可生成行动方案。
        </div>
      </el-card>

      <div class="min-w-0 space-y-4">
        <el-card v-if="!planList.length" shadow="never" class="border border-dashed border-slate-300">
          <el-empty description="还没有行动方案，先让 AI 职业规划专家为你规划吧">
            <el-button type="primary" @click="router.push('/')">去首页找 AI 规划助手</el-button>
          </el-empty>
        </el-card>

        <el-card v-else-if="!currentPlan" shadow="never" class="border border-dashed border-slate-300">
          <div class="py-12 text-center text-sm text-slate-500">
            <span v-if="detailLoading">正在加载方案正文…</span>
            <span v-else>请从左侧选择一份方案查看全文。</span>
          </div>
        </el-card>

        <el-card v-else shadow="never" class="border border-slate-200">
          <template #header>
            <div class="flex items-center justify-between gap-3">
              <div class="flex min-w-0 items-center gap-2">
                <el-tag v-if="isActivePlan(currentPlan)" size="small" type="success" effect="light">当前方案</el-tag>
                <el-tag v-else size="small" type="info" effect="plain">历史版本</el-tag>
                <h3 class="min-w-0 truncate text-base font-semibold text-slate-900">{{ currentPlan.title }}</h3>
              </div>
              <span class="shrink-0 text-xs text-slate-500">{{ formatDateTime(currentPlan.createdAt) }} 生成</span>
            </div>
          </template>

          <div class="plan-markdown markdown-body" v-loading="detailLoading" v-html="currentPlanHtml"></div>
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

.plan-markdown {
  min-height: 120px;
}

.markdown-body {
  line-height: 1.7;
  color: #1f2937;
  word-break: break-word;
  font-size: 14px;
}

.markdown-body :deep(p) {
  margin: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 0;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.35;
}

.markdown-body :deep(h1) {
  font-size: 24px;
}

.markdown-body :deep(h2) {
  font-size: 19px;
  margin-top: 20px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eef2f7;
}

.markdown-body :deep(h3) {
  font-size: 16px;
  margin-top: 16px;
}

.markdown-body :deep(h4) {
  font-size: 15px;
  margin-top: 14px;
}

.markdown-body :deep(h1 + *),
.markdown-body :deep(h3 + *),
.markdown-body :deep(h4 + *) {
  margin-top: 8px;
}

.markdown-body :deep(h2 + *) {
  margin-top: 8px;
}

.markdown-body :deep(p + p) {
  margin-top: 8px;
}

.markdown-body :deep(code) {
  padding: 1px 6px;
  border-radius: 6px;
  background: #f1f5f9;
  color: #0f172a;
  font-size: 12px;
}

.markdown-body :deep(pre) {
  margin-top: 8px;
  padding: 10px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  overflow-x: auto;
}

.markdown-body :deep(pre code) {
  background: transparent;
  padding: 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0 0;
  padding-left: 20px;
}

.markdown-body :deep(ul) {
  list-style: disc;
}

.markdown-body :deep(ol) {
  list-style: decimal;
}

.markdown-body :deep(li) {
  margin: 3px 0;
}

.markdown-body :deep(.md-table-scroll) {
  margin-top: 8px;
  overflow-x: auto;
  overflow-y: hidden;
  max-width: 100%;
  -webkit-overflow-scrolling: touch;
}

.markdown-body :deep(table) {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  margin-top: 0;
  border: 1px solid #dbe5f0;
  border-radius: 8px;
  overflow: hidden;
  font-size: 13px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #dbe5f0;
  padding: 6px 8px;
  text-align: left;
  vertical-align: top;
}

.markdown-body :deep(th) {
  background: #f8fafc;
  color: #0f172a;
  font-weight: 600;
}

.markdown-body :deep(blockquote) {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-left: 3px solid #93c5fd;
  background: #f8fbff;
  color: #334155;
}

.markdown-body :deep(a) {
  color: #2563eb;
  text-decoration: underline;
}

@media (max-width: 768px) {
  .markdown-body :deep(h1) {
    font-size: 20px;
  }

  .markdown-body :deep(h2) {
    font-size: 17px;
  }

  .markdown-body :deep(h3) {
    font-size: 15px;
  }

  .markdown-body :deep(h4) {
    font-size: 14px;
  }

  .markdown-body :deep(pre) {
    padding: 8px;
  }

  .markdown-body :deep(ul),
  .markdown-body :deep(ol) {
    padding-left: 18px;
  }

  .markdown-body :deep(th),
  .markdown-body :deep(td) {
    font-size: 12px;
    padding: 5px 6px;
    white-space: nowrap;
  }
}
</style>
