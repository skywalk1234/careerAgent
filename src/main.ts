import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { ElMessage } from 'element-plus'
import { useAppStore } from './stores/app'

const MESSAGE_OFFSET = 68

function withMessageDefaults(options: unknown): any {
	if (typeof options === 'string') {
		return {
			message: options,
			showClose: true,
			offset: MESSAGE_OFFSET,
		}
	}

	const normalized = (options ?? {}) as Record<string, unknown>
	const hasShowClose = Object.prototype.hasOwnProperty.call(normalized, 'showClose')
	const hasOffset = Object.prototype.hasOwnProperty.call(normalized, 'offset')

	const showClose = hasShowClose ? Boolean(normalized.showClose) : true
	const offset = hasOffset ? Number(normalized.offset) : MESSAGE_OFFSET

	return {
		...normalized,
		showClose,
		offset: Number.isFinite(offset) ? offset : MESSAGE_OFFSET,
	}
}

const rawSuccess = ElMessage.success.bind(ElMessage)
const rawWarning = ElMessage.warning.bind(ElMessage)
const rawInfo = ElMessage.info.bind(ElMessage)
const rawError = ElMessage.error.bind(ElMessage)

ElMessage.success = ((options: unknown, appContext?: unknown) =>
	rawSuccess(withMessageDefaults(options), appContext as any)) as typeof ElMessage.success
ElMessage.warning = ((options: unknown, appContext?: unknown) =>
	rawWarning(withMessageDefaults(options), appContext as any)) as typeof ElMessage.warning
ElMessage.info = ((options: unknown, appContext?: unknown) =>
	rawInfo(withMessageDefaults(options), appContext as any)) as typeof ElMessage.info
ElMessage.error = ((options: unknown, appContext?: unknown) =>
	rawError(withMessageDefaults(options), appContext as any)) as typeof ElMessage.error

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus)

const appStore = useAppStore(pinia)
appStore.hydrateProfileCacheFromStorage()

app.mount('#app')
