<script setup lang="ts">
import { computed } from 'vue'

interface ScoreItem {
  dimension: string
  score: number
}

const props = withDefaults(defineProps<{
  title?: string
  scores: ScoreItem[]
}>(), {
  title: '12维能力雷达图',
})

const size = 220
const cx = size / 2
const cy = size / 2
const radius = 78

const normalizedScores = computed(() => {
  const input = Array.isArray(props.scores) ? props.scores : []
  return input
    .slice(0, 12)
    .map((item) => ({
      dimension: String(item?.dimension || ''),
      score: Math.max(0, Math.min(100, Number(item?.score || 0))),
    }))
})

const points = computed(() => {
  const list = normalizedScores.value
  const total = list.length || 1
  return list.map((item, index) => {
    const angle = (-Math.PI / 2) + (2 * Math.PI * index) / total
    const ratio = Number(item.score || 0) / 100
    const x = cx + radius * ratio * Math.cos(angle)
    const y = cy + radius * ratio * Math.sin(angle)
    const lx = cx + (radius + 20) * Math.cos(angle)
    const ly = cy + (radius + 20) * Math.sin(angle)
    return {
      ...item,
      x,
      y,
      lx,
      ly,
    }
  })
})

const polygon = computed(() => points.value.map(point => `${point.x},${point.y}`).join(' '))

const ringPolygons = computed(() => {
  const ringRatio = [0.2, 0.4, 0.6, 0.8, 1]
  const total = normalizedScores.value.length || 1
  return ringRatio.map((ratio) => {
    const ring = normalizedScores.value.map((_item, index) => {
      const angle = (-Math.PI / 2) + (2 * Math.PI * index) / total
      const x = cx + radius * ratio * Math.cos(angle)
      const y = cy + radius * ratio * Math.sin(angle)
      return `${x},${y}`
    })
    return ring.join(' ')
  })
})
</script>

<template>
  <div class="assistant-radar-card">
    <p class="assistant-radar-title">{{ title }}</p>
    <svg :width="size" :height="size" viewBox="0 0 220 220" class="assistant-radar-svg" role="img" aria-label="能力雷达图">
      <polygon
        v-for="(ring, ringIndex) in ringPolygons"
        :key="`ring-${ringIndex}`"
        :points="ring"
        fill="none"
        stroke="#dbeafe"
        stroke-width="1"
      />

      <line
        v-for="(point, axisIndex) in points"
        :key="`axis-${axisIndex}`"
        :x1="cx"
        :y1="cy"
        :x2="point.lx"
        :y2="point.ly"
        stroke="#e2e8f0"
        stroke-width="1"
      />

      <polygon :points="polygon" fill="rgba(37,99,235,0.25)" stroke="#2563eb" stroke-width="2" />

      <circle
        v-for="(point, dotIndex) in points"
        :key="`dot-${dotIndex}`"
        :cx="point.x"
        :cy="point.y"
        r="3"
        fill="#1d4ed8"
      />

      <text
        v-for="(point, labelIndex) in points"
        :key="`label-${labelIndex}`"
        :x="point.lx"
        :y="point.ly"
        font-size="10"
        fill="#334155"
        text-anchor="middle"
        dominant-baseline="middle"
      >
        {{ point.dimension }}
      </text>
    </svg>
  </div>
</template>

<style scoped>
.assistant-radar-card {
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: linear-gradient(180deg, #f8fbff, #eef6ff);
  padding: 10px;
}

.assistant-radar-title {
  font-size: 12px;
  color: #1e3a8a;
  font-weight: 600;
  margin-bottom: 6px;
}

.assistant-radar-svg {
  width: 100%;
  height: auto;
  max-width: 240px;
  display: block;
  margin: 0 auto;
}
</style>
