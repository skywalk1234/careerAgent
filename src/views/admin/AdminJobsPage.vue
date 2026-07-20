<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Histogram, Refresh } from '@element-plus/icons-vue'
import { useAdminConsole } from '../../composables/useAdminConsole'

const {
  keyword,
  statusFilter,
  filteredJobs,
  jobCountSummary,
  jobCountSourceLabel,
  operationLogs,
  resolveLevelLabel,
  resolveStatusTagType,
  resolveStatusLabel,
  resetJobFilter,
} = useAdminConsole()

const currentPage = ref(1)
const pageSize = ref(8)

const pagedJobs = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredJobs.value.slice(start, start + pageSize.value)
})

const recentLogs = computed(() => operationLogs.value.slice(0, 12))

watch(filteredJobs, () => {
  currentPage.value = 1
})

function handlePageChange(page: number) {
  currentPage.value = page
}

function handleSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
}
</script>

<template>
  <div class="space-y-4">
    <section class="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm md:p-5">
      <h2 class="text-lg font-semibold text-slate-900">岗位数据管理</h2>
      <p class="mt-1 text-sm text-slate-500">支持岗位检索、状态筛选和运维日志联动查看</p>

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
          <p class="text-xs text-slate-500">已过期</p>
          <p class="mt-1 text-xl font-semibold text-rose-600">{{ jobCountSummary.expired }}</p>
        </div>
      </div>

      <p class="mt-3 text-xs text-slate-500">{{ jobCountSourceLabel }}</p>
    </section>

    <section class="grid gap-4 xl:grid-cols-12">
      <div class="space-y-4 xl:col-span-8">
        <el-card shadow="never" class="!rounded-2xl !border-slate-200">
          <template #header>
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="flex items-center gap-2 text-slate-800">
                <el-icon><Histogram /></el-icon>
                <span class="font-medium">岗位列表</span>
              </div>
              <div class="flex flex-wrap items-center gap-2">
                <el-input v-model="keyword" size="small" placeholder="按岗位/城市/来源检索" clearable class="!w-56" />
                <el-select v-model="statusFilter" size="small" class="!w-32">
                  <el-option label="全部状态" value="all" />
                  <el-option label="有效" value="active" />
                  <el-option label="即将过期" value="expiring" />
                  <el-option label="已过期" value="expired" />
                </el-select>
                <el-button size="small" @click="resetJobFilter">
                  <el-icon class="mr-1"><Refresh /></el-icon>
                  重置筛选
                </el-button>
              </div>
            </div>
          </template>

          <el-table :data="pagedJobs" stripe height="460">
            <el-table-column prop="jobId" label="岗位ID" min-width="118" />
            <el-table-column prop="jobName" label="岗位名称" min-width="160" />
            <el-table-column prop="city" label="城市" min-width="90" />
            <el-table-column label="级别" min-width="90">
              <template #default="scope">
                {{ resolveLevelLabel(scope.row.level) }}
              </template>
            </el-table-column>
            <el-table-column prop="source" label="来源" min-width="120" />
            <el-table-column prop="updatedAt" label="更新时间" min-width="150" />
            <el-table-column label="状态" min-width="110">
              <template #default="scope">
                <el-tag :type="resolveStatusTagType(scope.row.status)" size="small">
                  {{ resolveStatusLabel(scope.row.status) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>

          <div class="mt-3 flex justify-end">
            <el-pagination
              background
              :current-page="currentPage"
              :page-size="pageSize"
              :page-sizes="[8, 12, 20]"
              layout="total, sizes, prev, pager, next"
              :total="filteredJobs.length"
              @current-change="handlePageChange"
              @size-change="handleSizeChange"
            />
          </div>
        </el-card>
      </div>

      <div class="xl:col-span-4">
        <el-card shadow="never" class="!h-full !rounded-2xl !border-slate-200">
          <template #header>
            <div class="flex items-center justify-between">
              <span class="font-medium text-slate-800">运维日志</span>
              <span class="text-xs text-slate-500">{{ recentLogs.length }} 条</span>
            </div>
          </template>

          <div class="max-h-[560px] space-y-2 overflow-auto pr-1">
            <div
              v-for="log in recentLogs"
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
            <el-empty v-if="!recentLogs.length" description="暂无日志" :image-size="64" />
          </div>
        </el-card>
      </div>
    </section>
  </div>
</template>
