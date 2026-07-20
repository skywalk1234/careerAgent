<template>
    我的关键词
  <div class="word-cloud-chart">
    <div ref="chartRef" class="word-cloud-echarts"></div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import 'echarts-wordcloud'

const props = withDefaults(
  defineProps<{
    words: string[]
  }>(),
  {
    words: () => [],
  },
)

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let resizeTimer: ReturnType<typeof setTimeout> | null = null

function randomRGB() {
  const min = 50
  const r = Math.floor(Math.random() * (256 - min) + min)
  const g = Math.floor(Math.random() * (256 - min) + min)
  const b = Math.floor(Math.random() * (256 - min) + min)
  return `rgb(${r}, ${g}, ${b})`
}

function buildWordData(words: string[]) {
  return words.slice(0, 60).map((word, index) => {
    const seed = Array.from(word).reduce((sum, c) => sum + c.charCodeAt(0), 0)
    return {
      name: word,
      value: 18 + ((seed + index) % 46),
    }
  })
}

function renderChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }

  const list = buildWordData(props.words)
  chart.setOption(
    {
      animationDuration: 500,
      animationEasing: 'cubicOut',
      tooltip: {
        trigger: 'item',
        formatter: (params: { name: string; value: number }) => `${params.name} 权重：${params.value}`,
      },
      series: [
        {
          type: 'wordCloud',
          shape: 'circle',
          left: 'center',
          top: 'center',
          width: '100%',
          height: '100%',
          rotationRange: [0, 0],
          rotationStep: 45,
          gridSize: 2,
          sizeRange: [14, 42],
          drawOutOfBound: false,
          layoutAnimation: true,
          textStyle: {
            fontFamily: 'sans-serif',
            fontWeight: 700,
            color: randomRGB,
          },
          emphasis: {
            textStyle: {
              color: '#2563eb',
            },
          },
          data: list,
        },
      ],
    },
    true,
  )
}

function resizeChartDebounced() {
  if (resizeTimer) clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => {
    chart?.resize()
  }, 160)
}

onMounted(async () => {
  await nextTick()
  renderChart()
  window.addEventListener('resize', resizeChartDebounced)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChartDebounced)
  if (resizeTimer) clearTimeout(resizeTimer)
  chart?.dispose()
  chart = null
})

watch(
  () => props.words,
  () => {
    nextTick(() => {
      renderChart()
      resizeChartDebounced()
    })
  },
  { deep: true },
)
</script>

<style scoped>
.word-cloud-chart {
  min-height: 260px;
  border-radius: 10px;
  background: #ffffff;
}

.word-cloud-echarts {
  height: 260px;
  width: 100%;
}
</style>
