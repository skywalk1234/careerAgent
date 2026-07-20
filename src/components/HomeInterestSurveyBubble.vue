<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DataAnalysis } from '@element-plus/icons-vue'
import {
  getPreRecommendationIntentProfile,
  savePreRecommendationIntentProfile,
  type PreRecommendationIntentState,
  type PreRecommendationIntentSurveyPayload,
} from '../services/matchAnalysis'
import { isSuccessCode } from '../services/http'

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
  payload?: T
}

type QuestionType = 'single' | 'multi' | 'text'

interface QuestionOption {
  label: string
  value: string
}

interface SurveyQuestion {
  id: string
  type: QuestionType
  title: string
  subtitle?: string
  options?: QuestionOption[]
  maxSelect?: number
  placeholder?: string
  allowOtherInput?: boolean
  otherPlaceholder?: string
}

const OTHER_OPTION_VALUE = '__other__'

const surveyQuestions: SurveyQuestion[] = [
  {
    id: 'targetRole',
    type: 'single',
    title: '1. 当下最心仪/最想入职的岗位是？',
    subtitle: '单选（支持其他填写）',
    allowOtherInput: true,
    otherPlaceholder: '请输入你希望推荐的岗位名称',
    options: [
      { label: '前端开发工程师', value: '前端开发工程师' },
      { label: '后端开发工程师', value: '后端开发工程师' },
      { label: '全栈开发工程师', value: '全栈开发工程师' },
      { label: '测试开发工程师', value: '测试开发工程师' },
      { label: '数据分析师', value: '数据分析师' },
      { label: 'AI应用工程师', value: 'AI应用工程师' },
      { label: '其他（可填写）', value: OTHER_OPTION_VALUE },
    ],
  },
  {
    id: 'mbtiStyle',
    type: 'single',
    title: '2. 更接近你在团队中的状态是？',
    subtitle: '单选',
    options: [
      { label: '偏外向，沟通推动型', value: 'E' },
      { label: '偏内向，深度思考型', value: 'I' },
      { label: '场景切换，灵活型', value: 'X' },
    ],
  },
  {
    id: 'pressureStyle',
    type: 'single',
    title: '3. 遇到高压任务时，你通常会？',
    subtitle: '单选',
    options: [
      { label: '先拆解任务，再逐个击破', value: 'decompose' },
      { label: '先快速试错，边做边修正', value: 'try-first' },
      { label: '先求助团队，再协同推进', value: 'collaboration' },
    ],
  },
  {
    id: 'welfarePriorityMindset',
    type: 'single',
    title: '4. 你当前更看重哪类工作回报？',
    subtitle: '单选',
    options: [
      { label: '短期现金回报', value: 'short-cash' },
      { label: '中长期成长回报', value: 'growth' },
      { label: '稳定与平衡', value: 'balance' },
    ],
  },
  {
    id: 'cityDistance',
    type: 'single',
    title: '5. 工作城市距离你当前居住地，最多可接受多远？',
    subtitle: '单选',
    options: [
      { label: '同城或通勤圈内', value: 'same-city' },
      { label: '300km内（高铁可往返）', value: '300km' },
      { label: '跨省也可接受', value: 'cross-province' },
      { label: '全国都可以', value: 'nationwide' },
    ],
  },
  {
    id: 'cityIntents',
    type: 'multi',
    title: '6. 你优先考虑投递哪些城市？',
    subtitle: '多选（最多3项，支持其他填写）',
    maxSelect: 3,
    allowOtherInput: true,
    otherPlaceholder: '请输入你想投递的城市（如：苏州）',
    options: [
      { label: '西安', value: '西安' },
      { label: '北京', value: '北京' },
      { label: '上海', value: '上海' },
      { label: '深圳', value: '深圳' },
      { label: '杭州', value: '杭州' },
      { label: '成都', value: '成都' },
      { label: '其他（可填写）', value: OTHER_OPTION_VALUE },
    ],
  },
  {
    id: 'salaryExpectation',
    type: 'single',
    title: '7. 你目前可接受的薪资范围是？',
    subtitle: '单选（支持其他填写）',
    allowOtherInput: true,
    otherPlaceholder: '请输入可接受薪资范围（如：15-22k）',
    options: [
      { label: '8k以下', value: '8k以下' },
      { label: '8-12k', value: '8-12k' },
      { label: '12-18k', value: '12-18k' },
      { label: '18-25k', value: '18-25k' },
      { label: '25k以上', value: '25k以上' },
      { label: '其他（可填写）', value: OTHER_OPTION_VALUE },
    ],
  },
  {
    id: 'benefits',
    type: 'multi',
    title: '8. 你可接受并优先考虑的福利是？',
    subtitle: '多选（最多4项，支持其他填写）',
    maxSelect: 4,
    allowOtherInput: true,
    otherPlaceholder: '请输入你关注的福利（如：年度旅游）',
    options: [
      { label: '双休', value: '双休' },
      { label: '五险一金', value: '五险一金' },
      { label: '弹性办公', value: '弹性办公' },
      { label: '住房补贴', value: '住房补贴' },
      { label: '餐补', value: '餐补' },
      { label: '年度体检', value: '年度体检' },
      { label: '其他（可填写）', value: OTHER_OPTION_VALUE },
    ],
  },
  {
    id: 'workStyle',
    type: 'single',
    title: '9. 你理想中的工作方式更偏向？',
    subtitle: '单选',
    options: [
      { label: '技术深耕型', value: '技术深耕型' },
      { label: '跨团队协作型', value: '跨团队协作型' },
      { label: '稳定执行型', value: '稳定执行型' },
      { label: '快速挑战型', value: '快速挑战型' },
    ],
  },
  {
    id: 'personalityEnergy',
    type: 'single',
    title: '10. 你更容易在哪种环境中保持高效？',
    subtitle: '单选',
    options: [
      { label: '明确流程、节奏稳定', value: 'stable-process' },
      { label: '变化快、问题新', value: 'fast-change' },
      { label: '混合型，看项目阶段', value: 'hybrid' },
    ],
  },
  {
    id: 'careerNote',
    type: 'text',
    title: '11. 你还有哪些求职意愿想补充？',
    subtitle: '可选',
    placeholder: '例如：可接受短期出差，希望优先推荐培养机制完善的团队',
  },
]

const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const surveyState = ref<PreRecommendationIntentState | null>(null)
const currentQuestionIndex = ref(0)
const answerMap = reactive<Record<string, string | string[]>>({})
const skippedMap = reactive<Record<string, boolean>>({})
const otherInputMap = reactive<Record<string, string>>({})
const textInputExpandedMap = reactive<Record<string, boolean>>({})

const showBubble = computed(() => {
  const status = String(surveyState.value?.status || 'none')
  return !loading.value && status === 'none'
})

const currentQuestion = computed(() => surveyQuestions[currentQuestionIndex.value])
const questionCount = surveyQuestions.length
const isFirstQuestion = computed(() => currentQuestionIndex.value === 0)
const isLastQuestion = computed(() => currentQuestionIndex.value === questionCount - 1)
const questionProgress = computed(() => Math.round(((currentQuestionIndex.value + 1) / questionCount) * 100))

const answeredCount = computed(() => surveyQuestions.filter(question => hasQuestionAnswer(question.id)).length)
const skippedCount = computed(() => surveyQuestions.filter(question => skippedMap[question.id]).length)

const backendSurveyPayload = computed<PreRecommendationIntentSurveyPayload>(() => {
  const targetRole = resolveSingleAnswerForSubmit('targetRole')
  const cityIntents = resolveMultiAnswerForSubmit('cityIntents').slice(0, 3)
  const salaryExpectation = resolveSingleAnswerForSubmit('salaryExpectation')
  const benefits = resolveMultiAnswerForSubmit('benefits').slice(0, 4)
  const workStyle = getSingleAnswer('workStyle')
  const note = getTextAnswer('careerNote').trim().slice(0, 160)

  return {
    preferredJobKeywords: targetRole ? [targetRole] : [],
    cityIntents,
    salaryExpectation,
    benefits,
    workStyle,
    note,
  }
})

const hasBackendSignal = computed(() => {
  const payload = backendSurveyPayload.value
  return Boolean(
    (payload.preferredJobKeywords?.length || 0) > 0
    || (payload.cityIntents?.length || 0) > 0
    || String(payload.salaryExpectation || '').trim()
    || (payload.benefits?.length || 0) > 0,
  )
})

function hasQuestionAnswer(questionId: string) {
  const value = answerMap[questionId]
  if (typeof value === 'string') return Boolean(value.trim())
  if (Array.isArray(value)) return value.length > 0
  return false
}

function clearQuestionAnswer(questionId: string) {
  delete answerMap[questionId]
  delete otherInputMap[questionId]
  delete textInputExpandedMap[questionId]
}

function getSingleAnswer(questionId: string) {
  const value = answerMap[questionId]
  return typeof value === 'string' ? value : ''
}

function getMultiAnswer(questionId: string) {
  const value = answerMap[questionId]
  return Array.isArray(value) ? value : []
}

function getTextAnswer(questionId: string) {
  const value = answerMap[questionId]
  return typeof value === 'string' ? value : ''
}

function getOtherInput(questionId: string) {
  return String(otherInputMap[questionId] || '')
}

function updateOtherInput(questionId: string, value: string) {
  otherInputMap[questionId] = String(value || '').trim().slice(0, 50)
  skippedMap[questionId] = false
}

function isOtherValueSelected(questionId: string) {
  const value = answerMap[questionId]
  if (typeof value === 'string') {
    return value === OTHER_OPTION_VALUE
  }
  if (Array.isArray(value)) {
    return value.includes(OTHER_OPTION_VALUE)
  }
  return false
}

function isTextInputExpanded(questionId: string) {
  return Boolean(textInputExpandedMap[questionId] || getTextAnswer(questionId))
}

function openTextInput(questionId: string) {
  textInputExpandedMap[questionId] = true
  skippedMap[questionId] = false
}

function collapseTextInput(questionId: string) {
  if (!getTextAnswer(questionId)) {
    textInputExpandedMap[questionId] = false
  }
}

function resolveSingleAnswerForSubmit(questionId: string) {
  const selected = getSingleAnswer(questionId)
  if (selected !== OTHER_OPTION_VALUE) return selected
  return getOtherInput(questionId)
}

function resolveMultiAnswerForSubmit(questionId: string) {
  const list = getMultiAnswer(questionId)
  const filtered = list.filter(item => item !== OTHER_OPTION_VALUE)
  if (list.includes(OTHER_OPTION_VALUE)) {
    const otherText = getOtherInput(questionId)
    if (otherText) {
      filtered.push(otherText)
    }
  }
  return filtered.filter(Boolean)
}

function chooseSingle(question: SurveyQuestion, value: string) {
  answerMap[question.id] = value
  if (value !== OTHER_OPTION_VALUE) {
    delete otherInputMap[question.id]
  }
  skippedMap[question.id] = false
}

function toggleMulti(question: SurveyQuestion, value: string) {
  const maxSelect = Math.max(1, Number(question.maxSelect || 4))
  const current = [...getMultiAnswer(question.id)]
  const exists = current.includes(value)
  if (exists) {
    answerMap[question.id] = current.filter(item => item !== value)
    if (value === OTHER_OPTION_VALUE) {
      delete otherInputMap[question.id]
    }
    skippedMap[question.id] = false
    return
  }

  if (current.length >= maxSelect) {
    ElMessage.warning(`该题最多选择 ${maxSelect} 项`)
    return
  }

  answerMap[question.id] = [...current, value]
  skippedMap[question.id] = false
}

function updateTextAnswer(question: SurveyQuestion, value: string) {
  answerMap[question.id] = String(value || '').slice(0, 200)
  textInputExpandedMap[question.id] = true
  skippedMap[question.id] = false
}

function skipCurrentQuestion() {
  const question = currentQuestion.value
  if (!question) return

  clearQuestionAnswer(question.id)
  skippedMap[question.id] = true

  if (!isLastQuestion.value) {
    currentQuestionIndex.value += 1
  }
}

function goNextQuestion() {
  if (isLastQuestion.value) {
    ElMessage.info('已到最后一题，可直接提交测评')
    return
  }
  currentQuestionIndex.value += 1
}

function goPreviousQuestion() {
  if (isFirstQuestion.value) return
  currentQuestionIndex.value -= 1
}

function applySurveyState(state: PreRecommendationIntentState | null | undefined) {
  surveyState.value = state || { status: 'none' }

  const survey = state?.survey
  if (!survey) return

  const firstRole = Array.isArray(survey.preferredJobKeywords) ? String(survey.preferredJobKeywords[0] || '') : ''
  const cities = Array.isArray(survey.cityIntents) ? survey.cityIntents.slice(0, 3) : []
  const salaryExpectation = String(survey.salaryExpectation || '')
  const benefits = Array.isArray(survey.benefits) ? survey.benefits.slice(0, 4) : []
  const workStyle = String(survey.workStyle || '')
  const note = String(survey.note || '')

  if (firstRole) {
    const firstRoleMatched = (surveyQuestions.find(item => item.id === 'targetRole')?.options || [])
      .some(item => item.value === firstRole)
    if (firstRoleMatched) {
      answerMap.targetRole = firstRole
    } else {
      answerMap.targetRole = OTHER_OPTION_VALUE
      otherInputMap.targetRole = firstRole
    }
  }

  if (cities.length) {
    const optionValues = new Set((surveyQuestions.find(item => item.id === 'cityIntents')?.options || []).map(item => item.value))
    const matchedCities = cities.filter(item => optionValues.has(item))
    const customCity = cities.find(item => !optionValues.has(item)) || ''
    const merged = customCity ? [...matchedCities, OTHER_OPTION_VALUE] : matchedCities
    answerMap.cityIntents = merged.slice(0, 3)
    if (customCity) {
      otherInputMap.cityIntents = customCity
    }
  }
  if (salaryExpectation) {
    const salaryMatched = (surveyQuestions.find(item => item.id === 'salaryExpectation')?.options || [])
      .some(item => item.value === salaryExpectation)
    if (salaryMatched) {
      answerMap.salaryExpectation = salaryExpectation
    } else {
      answerMap.salaryExpectation = OTHER_OPTION_VALUE
      otherInputMap.salaryExpectation = salaryExpectation
    }
  }
  if (benefits.length) {
    const optionValues = new Set((surveyQuestions.find(item => item.id === 'benefits')?.options || []).map(item => item.value))
    const matchedBenefits = benefits.filter(item => optionValues.has(item))
    const customBenefit = benefits.find(item => !optionValues.has(item)) || ''
    const merged = customBenefit ? [...matchedBenefits, OTHER_OPTION_VALUE] : matchedBenefits
    answerMap.benefits = merged.slice(0, 4)
    if (customBenefit) {
      otherInputMap.benefits = customBenefit
    }
  }
  if (workStyle) answerMap.workStyle = workStyle
  if (note) {
    answerMap.careerNote = note
    textInputExpandedMap.careerNote = true
  }
}

async function fetchSurveyState() {
  loading.value = true
  try {
    const response = await getPreRecommendationIntentProfile()
    const payload = response.data as ApiResponse<PreRecommendationIntentState>
    if (isSuccessCode(Number(payload.code || 0))) {
      applySurveyState(payload.payload ?? payload.data)
      return
    }
    applySurveyState({ status: 'none' })
  } catch {
    applySurveyState({ status: 'none' })
  } finally {
    loading.value = false
  }
}

async function saveSurvey(skip = false) {
  if (submitting.value) return

  let shouldSkip = skip
  if (!shouldSkip && !hasBackendSignal.value) {
    ElMessage.info('未填写可用于收敛推荐范围的题目，本次将按跳过处理')
    shouldSkip = true
  }

  submitting.value = true
  try {
    const response = await savePreRecommendationIntentProfile(
      shouldSkip
        ? {
            survey: {},
            topN: 6,
          }
        : {
            survey: backendSurveyPayload.value,
            topN: 6,
          },
    )

    const payload = response.data as ApiResponse<PreRecommendationIntentState>
    if (!isSuccessCode(Number(payload.code || 0))) {
      throw new Error(payload.msg || '保存失败')
    }

    applySurveyState(payload.payload ?? payload.data)
    dialogVisible.value = false

    if (shouldSkip) {
      ElMessage.success('已完成测评流程，本次按跳过处理')
      return
    }

    const recommendationStatus = String((payload.payload ?? payload.data)?.recommendationStatus || '')
    if (recommendationStatus === 'processing') {
      ElMessage.success('测评已保存，已触发推荐范围更新')
    } else {
      ElMessage.success('测评已保存，完成画像后将用于收敛推荐范围')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

function handleOpenDialog() {
  if (loading.value) return
  currentQuestionIndex.value = 0
  dialogVisible.value = true
}

onMounted(async () => {
  await fetchSurveyState()
})
</script>

<template>
  <div v-if="showBubble" class="intent-bubble-wrap">
    <el-tooltip content="职业兴趣测评" placement="left">
      <button type="button" class="intent-bubble" @click="handleOpenDialog">
        <el-icon class="intent-bubble-icon"><DataAnalysis /></el-icon>
      </button>
    </el-tooltip>
  </div>

  <el-dialog
    v-model="dialogVisible"
    width="min(760px, calc(100vw - 24px))"
    :close-on-click-modal="true"
    :show-close="!submitting"
    destroy-on-close
    class="intent-survey-dialog"
  >
    <div class="intent-survey-shell">
      <header class="survey-header">
        <div>
          <p class="survey-kicker">Career Intention Quiz</p>
          <h3 class="survey-title">职业兴趣小测</h3>
          <p class="survey-subtitle">共 {{ questionCount }} 题</p>
        </div>
        <div class="survey-stats">
          <span>已答 {{ answeredCount }}</span>
          <span>已跳过 {{ skippedCount }}</span>
        </div>
      </header>

      <el-progress :percentage="questionProgress" :stroke-width="8" color="#60a5fa" :show-text="false" />

      <transition name="question-slide" mode="out-in">
        <article :key="currentQuestion.id" class="question-card">
          <p class="question-index">第 {{ currentQuestionIndex + 1 }} / {{ questionCount }} 题</p>
          <h4 class="question-title">{{ currentQuestion.title }}</h4>
          <p v-if="currentQuestion.subtitle" class="question-subtitle">{{ currentQuestion.subtitle }}</p>

          <template v-if="currentQuestion.type === 'single'">
            <div class="option-grid">
              <button
                v-for="option in currentQuestion.options || []"
                :key="`${currentQuestion.id}-${option.value}`"
                type="button"
                class="option-card"
                :class="[
                  option.value === OTHER_OPTION_VALUE ? 'is-other-option' : '',
                  getSingleAnswer(currentQuestion.id) === option.value ? 'is-selected' : '',
                ]"
                @click="chooseSingle(currentQuestion, option.value)"
              >
                {{ option.label }}
              </button>
            </div>

            <div
              v-if="currentQuestion.allowOtherInput && isOtherValueSelected(currentQuestion.id)"
              class="other-input-wrap"
            >
              <el-input
                :model-value="getOtherInput(currentQuestion.id)"
                maxlength="50"
                show-word-limit
                :placeholder="currentQuestion.otherPlaceholder || '请输入其他选项'"
                @update:model-value="updateOtherInput(currentQuestion.id, String($event || ''))"
              />
            </div>
          </template>

          <template v-else-if="currentQuestion.type === 'multi'">
            <div class="option-grid">
              <button
                v-for="option in currentQuestion.options || []"
                :key="`${currentQuestion.id}-${option.value}`"
                type="button"
                class="option-card"
                :class="[
                  option.value === OTHER_OPTION_VALUE ? 'is-other-option' : '',
                  getMultiAnswer(currentQuestion.id).includes(option.value) ? 'is-selected' : '',
                ]"
                @click="toggleMulti(currentQuestion, option.value)"
              >
                {{ option.label }}
              </button>
            </div>

            <div
              v-if="currentQuestion.allowOtherInput && isOtherValueSelected(currentQuestion.id)"
              class="other-input-wrap"
            >
              <el-input
                :model-value="getOtherInput(currentQuestion.id)"
                maxlength="50"
                show-word-limit
                :placeholder="currentQuestion.otherPlaceholder || '请输入其他选项'"
                @update:model-value="updateOtherInput(currentQuestion.id, String($event || ''))"
              />
            </div>
          </template>

          <template v-else-if="currentQuestion.type === 'text'">
            <div v-if="!isTextInputExpanded(currentQuestion.id)" class="text-answer-collapsed">
              <el-button plain type="primary" @click="openTextInput(currentQuestion.id)">添加补充说明</el-button>
            </div>

            <div v-else class="text-answer-wrap">
              <el-input
                :model-value="getTextAnswer(currentQuestion.id)"
                type="textarea"
                :rows="4"
                maxlength="200"
                show-word-limit
                resize="none"
                :placeholder="currentQuestion.placeholder || '请输入具体求职条件'"
                @update:model-value="updateTextAnswer(currentQuestion, String($event || ''))"
              />
              <div class="text-answer-actions">
                <el-button text type="primary" @click="collapseTextInput(currentQuestion.id)">取消</el-button>
              </div>
            </div>
          </template>

          <div class="question-actions">
            <el-button text type="primary" @click="skipCurrentQuestion">跳过</el-button>

            <div class="question-nav">
              <el-button :disabled="isFirstQuestion" @click="goPreviousQuestion">上一题</el-button>
              <el-button type="primary" @click="goNextQuestion" v-if="!isLastQuestion">下一题</el-button>
            </div>
          </div>
        </article>
      </transition>

    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button :disabled="submitting" @click="saveSurvey(true)">全部跳过</el-button>
        <el-button type="primary" :loading="submitting" @click="saveSurvey(false)">提交测评</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.intent-bubble-wrap {
  position: fixed;
  right: 16px;
  top: 34%;
  z-index: 32;
}

.intent-bubble {
  width: 56px;
  height: 56px;
  border: 1px solid #60a5fa;
  border-radius: 50%;
  background: #e0efff;
  color: #1d4ed8;
  display: grid;
  place-items: center;
  box-shadow: 0 8px 16px rgba(59, 130, 246, 0.22);
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}

.intent-bubble:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 20px rgba(59, 130, 246, 0.28);
}

.intent-bubble-icon {
  font-size: 22px;
}

.intent-survey-shell {
  border: 1px solid #dbeafe;
  border-radius: 14px;
  background: linear-gradient(180deg, #f9fcff 0%, #eff6ff 100%);
  padding: 14px;
}

.survey-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.survey-kicker {
  margin: 0;
  color: #2563eb;
  font-size: 11px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  font-weight: 700;
}

.survey-title {
  margin: 2px 0 0;
  color: #111827;
  font-size: 18px;
  font-weight: 700;
}

.survey-subtitle {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 12px;
}

.survey-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #1d4ed8;
}

.survey-stats span {
  border: 1px solid #bfdbfe;
  background: #dbeafe;
  border-radius: 999px;
  padding: 2px 8px;
  white-space: nowrap;
}

.question-card {
  margin-top: 12px;
  border: 1px solid #dbeafe;
  border-radius: 12px;
  background: #fff;
  padding: 14px;
}

.question-index {
  margin: 0;
  font-size: 12px;
  color: #2563eb;
  font-weight: 600;
}

.question-title {
  margin: 8px 0 0;
  font-size: 16px;
  color: #111827;
  line-height: 1.45;
}

.question-subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: #64748b;
}

.option-grid {
  margin-top: 12px;
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.option-card {
  border: 1px solid #dbeafe;
  background: #f7fbff;
  color: #1f2937;
  border-radius: 10px;
  padding: 10px 12px;
  text-align: left;
  font-size: 13px;
  line-height: 1.4;
  transition: border-color 0.18s ease, background-color 0.18s ease, transform 0.18s ease;
}

.option-card:hover {
  border-color: #60a5fa;
  transform: translateY(-1px);
}

.option-card.is-selected {
  border-color: #3b82f6;
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 600;
}

.option-card.is-other-option {
  border-style: dashed;
}

.other-input-wrap {
  margin-top: 10px;
}

.text-answer-wrap {
  margin-top: 12px;
}

.text-answer-collapsed {
  margin-top: 12px;
  border: 1px dashed #bfdbfe;
  border-radius: 10px;
  background: #f7fbff;
  padding: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.text-answer-collapsed-tip {
  margin: 0;
  font-size: 12px;
  color: #64748b;
}

.text-answer-actions {
  margin-top: 6px;
  display: flex;
  justify-content: flex-end;
}

.question-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.question-nav {
  display: flex;
  align-items: center;
  gap: 8px;
}

.survey-tip {
  margin: 10px 0 0;
  font-size: 12px;
  color: #1d4ed8;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.question-slide-enter-active,
.question-slide-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.question-slide-enter-from {
  opacity: 0;
  transform: translateX(16px);
}

.question-slide-leave-to {
  opacity: 0;
  transform: translateX(-16px);
}

@media (max-width: 900px) {
  .intent-bubble-wrap {
    right: 10px;
    top: auto;
    bottom: 110px;
  }

  .intent-bubble {
    width: 50px;
    height: 50px;
  }

  .intent-bubble-icon {
    font-size: 20px;
  }

  .survey-header {
    flex-direction: column;
  }

  .option-grid {
    grid-template-columns: 1fr;
  }

  .question-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .question-nav {
    justify-content: flex-end;
  }

  .text-answer-collapsed {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
