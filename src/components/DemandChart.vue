<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts'

const chartEl = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const option: echarts.EChartsCoreOption = {
  tooltip: { trigger: 'axis' },
  grid: { left: 36, right: 18, top: 30, bottom: 28 },
  xAxis: {
    type: 'category',
    data: ['前端', '后端', '数据分析', '测试', '运维', 'AI工程'],
  },
  yAxis: { type: 'value' },
  series: [
    {
      name: '岗位需求指数',
      type: 'bar',
      data: [74, 88, 66, 53, 48, 92],
      itemStyle: { color: '#2563eb' },
      barMaxWidth: 38,
    },
  ],
}

const resize = () => chart?.resize()

onMounted(() => {
  if (!chartEl.value) return
  chart = echarts.init(chartEl.value)
  chart.setOption(option)
  window.addEventListener('resize', resize)
})

onUnmounted(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
</script>

<template>
  <div class="rounded-xl border bg-white p-4">
    <h3 class="mb-3 text-sm font-semibold text-slate-700">应届生岗位需求趋势（示例）</h3>
    <div ref="chartEl" class="h-[280px] w-full" />
  </div>
</template>
