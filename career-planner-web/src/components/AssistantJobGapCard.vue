<script setup lang="ts">
interface GapItem {
  dimension: string
  currentScore: number
  targetScore: number
  gap: number
  direction?: string
}

const { title, items } = defineProps<{
  title?: string
  items: GapItem[]
}>()
</script>

<template>
  <div class="assistant-gap-card" v-if="Array.isArray(items) && items.length">
    <p class="assistant-gap-title">{{ title || '岗位差距看板' }}</p>
    <div class="assistant-gap-list">
      <div v-for="(item, index) in items" :key="`${item.dimension}-${index}`" class="assistant-gap-item">
        <div class="assistant-gap-row">
          <span class="assistant-gap-dimension">{{ item.dimension }}</span>
          <span class="assistant-gap-value" :class="item.direction === 'ahead' ? 'is-ahead' : 'is-behind'">
            {{ item.direction === 'ahead' ? '领先' : '差距' }} {{ item.gap }}
          </span>
        </div>
        <div class="assistant-gap-track">
          <i class="assistant-gap-current" :style="{ width: `${Math.max(0, Math.min(100, Number(item.currentScore || 0)))}%` }"></i>
          <b class="assistant-gap-target" :style="{ left: `${Math.max(0, Math.min(100, Number(item.targetScore || 0)))}%` }"></b>
        </div>
        <div class="assistant-gap-meta">
          <span>当前 {{ item.currentScore }}</span>
          <span>目标 {{ item.targetScore }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.assistant-gap-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 10px;
  background: #ffffff;
}

.assistant-gap-title {
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 8px;
}

.assistant-gap-list {
  display: grid;
  gap: 8px;
}

.assistant-gap-item {
  border: 1px solid #edf2f7;
  border-radius: 10px;
  padding: 8px;
  background: #f8fafc;
}

.assistant-gap-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.assistant-gap-dimension {
  font-size: 12px;
  color: #334155;
}

.assistant-gap-value {
  font-size: 11px;
  font-weight: 600;
}

.assistant-gap-value.is-behind {
  color: #b45309;
}

.assistant-gap-value.is-ahead {
  color: #15803d;
}

.assistant-gap-track {
  position: relative;
  height: 8px;
  border-radius: 999px;
  background: #e2e8f0;
  overflow: hidden;
}

.assistant-gap-current {
  position: absolute;
  inset: 0 auto 0 0;
  background: linear-gradient(90deg, #60a5fa, #2563eb);
}

.assistant-gap-target {
  position: absolute;
  top: -2px;
  width: 2px;
  height: 12px;
  background: #0f172a;
}

.assistant-gap-meta {
  margin-top: 4px;
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #64748b;
}
</style>
