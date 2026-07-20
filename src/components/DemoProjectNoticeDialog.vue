<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm'): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

function handleConfirm() {
  emit('confirm')
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="演示项目说明"
    width="640px"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
  >
    <div class="space-y-3 text-sm leading-6 text-slate-700">
      <p>考虑到密钥泄漏与安全隐患，前端不提供输入或保存API Key的功能，仅本地部署时通过env配置接入大模型供功能验证与测试。</p>
      <p>当前为云端部署展示，仅进行项目流程与职业规划流程演示，每日重置账号信息与清理过期导出文件；核心接口不接入真实大模型服务。</p>
      <p>若企业需要接入真实大模型检查，请通过团队提交的脚本自行修改替换API Key并完成学生画像评估、人岗匹配等能力指标验证。</p>
    </div>

    <template #footer>
      <el-button type="primary" @click="handleConfirm">我已知晓，继续使用</el-button>
    </template>
  </el-dialog>
</template>
