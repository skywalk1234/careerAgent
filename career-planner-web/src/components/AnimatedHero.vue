<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import * as THREE from 'three'
import NET from 'vanta/dist/vanta.net.min'
import brandLogo from '../assets/favicon.png'

const heroRef = ref<HTMLElement | null>(null)
let vantaEffect: { destroy: () => void } | null = null

onMounted(() => {
  if (!heroRef.value) return

  vantaEffect = NET({
    el: heroRef.value,
    THREE,
    mouseControls: true,
    touchControls: true,
    gyroControls: false,
    minHeight: 280,
    minWidth: 300,
    scale: 1,
    scaleMobile: 1,
    color: 0x2c7be5,
    backgroundColor: 0x0f172a,
    points: 10,
    maxDistance: 20,
    spacing: 18,
  }) as { destroy: () => void }
})

onUnmounted(() => {
  if (vantaEffect) {
    vantaEffect.destroy()
  }
})
</script>

<template>
  <div ref="heroRef" class="relative h-[300px] w-full overflow-hidden rounded-xl">
    <div class="absolute inset-0 bg-slate-900/35" />
    <div class="absolute inset-0 flex items-center px-6 md:px-10">
      <div class="max-w-xl text-white">
        <h1 class="flex items-center gap-2 text-2xl font-bold md:text-4xl">
          <img :src="brandLogo" alt="微光职引" class="h-7 w-7 rounded md:h-9 md:w-9" />
          <span>微光职引</span>
        </h1>
        <p class="mt-3 text-sm text-slate-100 md:text-base">
          聚合岗位画像、学生画像与职业路径，快速生成可解释、可执行的成长计划。
        </p>
      </div>
    </div>
  </div>
</template>
