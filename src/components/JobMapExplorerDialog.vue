<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import {
  getJobMapCityStats,
  getJobMapFilters,
  getJobMapPopup,
  getJobMapProvinceGeoJson,
  getJobMapProvinceStats,
  getJobMapRegionJobs,
  type GeoJsonFeatureCollection,
  type JobMapCityStat,
  type JobMapFilterOptions,
  type JobMapRegionJobItem,
  type JobMapRegionJobsResult,
  type JobMapProvinceStat,
  type JobMapQuery,
} from '../services/jobMap'
import { isSuccessCode } from '../services/http'

const props = defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'select-job', payload: { jobId: string }): void
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const containerRef = ref<HTMLElement | null>(null)
const mapLoading = ref(false)
const mapError = ref('')
const mapLevel = ref<'country' | 'province'>('country')
const currentProvince = ref<{ name: string; adcode: number } | null>(null)

const loadProgress = reactive({
  visible: false,
  percent: 0,
  text: '',
})

const filterOptions = reactive<JobMapFilterOptions>({
  jobFamilies: [],
  levels: [],
  cityTiers: [],
  provinces: [],
})

const filters = reactive({
  keyword: '',
  jobFamilies: [] as string[],
  jobLevel: '',
  cityTier: '',
})

const effects = reactive({
  mode: 'concise' as 'concise' | 'enhanced',
  showAllLabels: false,
  prism: true,
  flyline: false,
  texture: false,
})

const popup = reactive({
  visible: false,
  x: 0,
  y: 0,
  level: 'province' as 'province' | 'city',
  adcode: 0,
  jobCount: 0,
  title: '',
  lines: [] as string[],
})

const regionJobs = reactive({
  visible: false,
  loading: false,
  title: '',
  total: 0,
  list: [] as JobMapRegionJobItem[],
})
const regionJobsKeyword = ref('')
const filteredRegionJobs = computed(() => {
  const keyword = String(regionJobsKeyword.value || '').trim().toLowerCase()
  if (!keyword) return regionJobs.list

  return regionJobs.list.filter((item) => {
    const fields = [
      item.jobName,
      item.companyName,
      item.city,
      item.district,
      item.salaryNormalized,
    ]
      .map(value => String(value || '').toLowerCase())
      .filter(Boolean)

    return fields.some(value => value.includes(keyword))
  })
})

interface ApiResponse<T> {
  code: number
  msg: string
  data?: T
  payload?: T
}

interface GeoFeature {
  type: 'Feature'
  properties: {
    adcode?: number
    name?: string
    center?: [number, number]
    [key: string]: any
  }
  geometry: {
    type: 'Polygon' | 'MultiPolygon'
    coordinates: any[]
  }
}

type RegionStat = JobMapProvinceStat | JobMapCityStat

interface RegionMeta {
  level: 'province' | 'city'
  adcode: number
  name: string
  center: [number, number]
  mesh: THREE.Mesh
  container: THREE.Object3D
  topMaterial: THREE.MeshStandardMaterial
  sideMaterial: THREE.MeshBasicMaterial
  baseColor: THREE.Color
  labelSprite?: THREE.Sprite
}

interface FlyLineMeta {
  curve: THREE.CatmullRomCurve3
  marker: THREE.Mesh
  progress: number
  speed: number
}

let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let renderer: THREE.WebGLRenderer | null = null
let controls: OrbitControls | null = null
let raycaster: THREE.Raycaster | null = null
let pointer = new THREE.Vector2(0, 0)
let animationFrameId: number | null = null
let resizeObserver: ResizeObserver | null = null
let progressHideTimer: ReturnType<typeof setTimeout> | null = null
let animationClock: THREE.Clock | null = null

let mapRoot: THREE.Group | null = null
let regionGroup: THREE.Group | null = null
let prismGroup: THREE.Group | null = null
let flyLineGroup: THREE.Group | null = null
let backgroundFxGroup: THREE.Group | null = null
let outerRingMesh: THREE.Mesh | null = null
let innerRingMesh: THREE.Mesh | null = null
let gradientPlaneMesh: THREE.Mesh | null = null

let pickableMeshes: THREE.Mesh[] = []
let regionMetaByAdcode = new Map<number, RegionMeta>()
let hoveredRegion: RegionMeta | null = null
let selectedRegion: RegionMeta | null = null
let popupRequestToken = 0
let regionJobsRequestToken = 0
let flyLineState: FlyLineMeta[] = []
let currentLayerLevel: 'province' | 'city' = 'province'
let currentLayerSpan = 220
let mapScaleFactor = 1
let countryReferenceSpan = 0

const BASE_PROJECTION_CENTER_LON = 107.067641
const BASE_PROJECTION_CENTER_LAT = 36.226277
const BASE_PROJECTION_SCALE = 152.95

let projectionCenterLon = BASE_PROJECTION_CENTER_LON
let projectionCenterLat = BASE_PROJECTION_CENTER_LAT
let projectionCenterMercatorY = mercatorY(BASE_PROJECTION_CENTER_LAT)
let projectionScale = BASE_PROJECTION_SCALE

let mapTexture: THREE.Texture | null = null
let mapTexturePromise: Promise<THREE.Texture | null> | null = null
let ringOuterTexture: THREE.Texture | null = null
let ringInnerTexture: THREE.Texture | null = null
let ringTexturePromise: Promise<{ outer: THREE.Texture | null; inner: THREE.Texture | null }> | null = null
let textureFailureNotified = false

const mapUf = {
  uTime: { value: 0.0 },
  uHeight: { value: 10.0 },
  uColor: { value: new THREE.Color('#1EB4E6') },
  uStart: { value: -10.0 },
  uSpeed: { value: 6.0 },
}

const planeUf = {
  center: { value: new THREE.Vector2(0.5, 0.5) },
  radius: { value: 0.24 },
  color1: { value: new THREE.Color('#011024') },
  color2: { value: new THREE.Color('#00BFFF') },
  opacitys: { value: 0.72 },
}

const BACKGROUND_RING_BASE_OUTER = 760
const BACKGROUND_RING_BASE_INNER = 520

let provinceStatsMap = new Map<number, JobMapProvinceStat>()
let cityStatsMap = new Map<number, JobMapCityStat>()
const cityLayerCache = new Map<number, { features: GeoFeature[]; cityStats: Map<number, JobMapCityStat> }>()

function unwrapApi<T>(response: { data: ApiResponse<T> }) {
  const body = response.data
  return {
    code: Number(body?.code),
    msg: String(body?.msg || ''),
    payload: (body?.payload ?? body?.data) as T | undefined,
  }
}

function toMapQuery(): JobMapQuery {
  return {
    keyword: filters.keyword.trim() || undefined,
    jobFamilies: filters.jobFamilies.length ? [...filters.jobFamilies] : undefined,
    level: filters.jobLevel || undefined,
    cityTier: filters.cityTier || undefined,
  }
}

function normalizeJobLevel(value: string) {
  const normalized = String(value || '').trim().toLowerCase()
  if (normalized === 'mid') return 'middle'
  return normalized
}

function toJobLevelLabel(value: string) {
  const level = normalizeJobLevel(value)
  if (level === 'junior') return '低级'
  if (level === 'middle') return '中级'
  if (level === 'senior') return '高级'
  if (level === 'lead') return '资深'
  return value
}

async function resetFilters() {
  filters.keyword = ''
  filters.jobFamilies = []
  filters.jobLevel = ''
  filters.cityTier = ''
  await refreshCurrentLayer()
}

function syncEffectMode() {
  effects.mode = effects.flyline ? 'enhanced' : 'concise'
}

function formatSalary(value: number | null | undefined): string {
  if (!Number.isFinite(Number(value))) return '暂无'
  return `${Math.round(Number(value))} 元/月`
}

function formatDateYmd(value: string | null | undefined) {
  const text = String(value || '').trim()
  if (!text) return '发布日期未知'
  const date = new Date(text)
  if (Number.isNaN(date.getTime())) return '发布日期未知'
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}年${month}月${day}日`
}

function hideRegionJobsPanel() {
  regionJobsKeyword.value = ''
  regionJobs.visible = false
  regionJobs.loading = false
  regionJobs.title = ''
  regionJobs.total = 0
  regionJobs.list = []
}

async function loadRegionJobsByPopup() {
  if (!popup.level || !popup.adcode) return

  regionJobs.loading = true
  const token = ++regionJobsRequestToken
  try {
    const response = await getJobMapRegionJobs({
      level: popup.level,
      adcode: popup.adcode,
      jobFamilies: filters.jobFamilies.length ? [...filters.jobFamilies] : undefined,
      jobLevel: filters.jobLevel || undefined,
      cityTier: filters.cityTier || undefined,
      keyword: filters.keyword.trim() || undefined,
      page: 1,
      pageSize: 40,
    })
    const body = unwrapApi<JobMapRegionJobsResult>(response)
    if (token !== regionJobsRequestToken) return
    if (!isSuccessCode(body.code) || !body.payload) {
      throw new Error(body.msg || '岗位详情列表加载失败')
    }

    const payload = body.payload
    const list = Array.isArray(payload.list) ? payload.list : []
    regionJobsKeyword.value = ''
    regionJobs.title = payload.level === 'city'
      ? String(payload.cityName || popup.title || '')
      : String(payload.provinceName || popup.title || '')
    regionJobs.total = Number(payload.total || list.length || 0)
    regionJobs.list = list
    regionJobs.visible = true
  } catch (error: any) {
    if (token !== regionJobsRequestToken) return
    ElMessage.warning(error?.message || '岗位详情列表加载失败')
  } finally {
    if (token === regionJobsRequestToken) {
      regionJobs.loading = false
    }
  }
}

async function toggleRegionJobsPanel() {
  if (!popup.visible || !popup.jobCount) return
  if (regionJobs.visible) {
    hideRegionJobsPanel()
    return
  }
  await loadRegionJobsByPopup()
}

function handleRegionJobSelect(item: JobMapRegionJobItem) {
  const jobId = String(item?.jobId || '').trim()
  if (!jobId) return
  emit('select-job', { jobId })
  visible.value = false
}

function showNoJobPopup(meta: RegionMeta, data: Record<string, any> = {}) {
  popup.level = meta.level === 'city' ? 'city' : 'province'
  popup.adcode = Number(meta.adcode || 0)
  popup.jobCount = 0
  popup.title = meta.name
  popup.lines = [
    '该地区暂无目标岗位',
    `岗位分类数：${Number(data?.jobCount || 0)}`,
    `具体岗位数量：${Number(data?.jdCount || 0)}`,
  ]
  setPopupVisible(true)
}

function setPopupVisible(value: boolean) {
  popup.visible = value
  if (!value) {
    hideRegionJobsPanel()
  }
  refreshLabelVisibility()
}

function beginOpenProgress(text: string, percent = 6) {
  if (progressHideTimer) {
    clearTimeout(progressHideTimer)
    progressHideTimer = null
  }
  loadProgress.visible = true
  loadProgress.text = text
  loadProgress.percent = Math.max(0, Math.min(100, percent))
}

async function waitForContainerReady(maxTries = 30) {
  for (let i = 0; i < maxTries; i += 1) {
    await nextTick()
    const el = containerRef.value
    if (el && el.clientWidth > 0 && el.clientHeight > 0) {
      return true
    }
    await new Promise(resolve => setTimeout(resolve, 30))
  }
  return false
}

function updateOpenProgress(text: string, percent: number) {
  loadProgress.text = text
  loadProgress.percent = Math.max(0, Math.min(100, percent))
}

function finishOpenProgress() {
  loadProgress.text = '地图加载完成'
  loadProgress.percent = 100
  progressHideTimer = setTimeout(() => {
    loadProgress.visible = false
  }, 320)
}

function loadTexture(path: string): Promise<THREE.Texture | null> {
  return new Promise((resolve) => {
    const loader = new THREE.TextureLoader()
    loader.load(
      path,
      (texture) => resolve(texture),
      undefined,
      () => resolve(null),
    )
  })
}

function ensureRingTextures() {
  if (ringOuterTexture && ringInnerTexture) {
    return Promise.resolve({ outer: ringOuterTexture, inner: ringInnerTexture })
  }
  if (ringTexturePromise) return ringTexturePromise

  ringTexturePromise = Promise.all([
    loadTexture('/mapPublic/rotationBorder1.png'),
    loadTexture('/mapPublic/rotationBorder2.png'),
  ]).then(([outer, inner]) => {
    ringOuterTexture = outer
    ringInnerTexture = inner
    return { outer, inner }
  })

  return ringTexturePromise
}

async function createBackgroundFxLayer() {
  if (!scene) return

  if (backgroundFxGroup) {
    scene.remove(backgroundFxGroup)
    disposeObject3D(backgroundFxGroup)
  }

  const textures = await ensureRingTextures()
  if (!scene) return

  const group = new THREE.Group()
  group.name = 'backgroundFxGroup'

  if (textures.outer) {
    const outerMaterial = new THREE.MeshBasicMaterial({
      map: textures.outer,
      transparent: true,
      opacity: 0.92,
      side: THREE.DoubleSide,
      depthWrite: false,
      depthTest: true,
    })
    outerRingMesh = new THREE.Mesh(
      new THREE.PlaneGeometry(BACKGROUND_RING_BASE_OUTER, BACKGROUND_RING_BASE_OUTER),
      outerMaterial,
    )
    outerRingMesh.renderOrder = -2
    outerRingMesh.position.set(0, -11, 0)
    outerRingMesh.rotateX(Math.PI / 2)
    group.add(outerRingMesh)
  }

  if (textures.inner) {
    const innerMaterial = new THREE.MeshBasicMaterial({
      map: textures.inner,
      transparent: true,
      opacity: 0.86,
      side: THREE.DoubleSide,
      depthWrite: false,
      depthTest: true,
    })
    innerRingMesh = new THREE.Mesh(
      new THREE.PlaneGeometry(BACKGROUND_RING_BASE_INNER, BACKGROUND_RING_BASE_INNER),
      innerMaterial,
    )
    innerRingMesh.renderOrder = -1
    innerRingMesh.position.set(0, -11, 0)
    innerRingMesh.rotateX(Math.PI / 2)
    group.add(innerRingMesh)
  }

  const gradientMaterial = new THREE.MeshBasicMaterial({
    transparent: true,
    depthWrite: false,
    depthTest: true,
    side: THREE.DoubleSide,
  })
  gradientMaterial.onBeforeCompile = (shader) => {
    shader.uniforms = {
      ...shader.uniforms,
      ...planeUf,
    }
    shader.vertexShader = shader.vertexShader.replace(
      'void main() {',
      `
          varying vec2 vUv;
          void main() {
            vUv = uv;
      `,
    )
    shader.fragmentShader = shader.fragmentShader.replace(
      'void main() {',
      `
          varying vec2 vUv;
          uniform vec2 center;
          uniform float radius;
          uniform vec3 color1;
          uniform vec3 color2;
          uniform float opacitys;
          void main() {
      `,
    )
    shader.fragmentShader = shader.fragmentShader.replace(
      '#include <output_fragment>',
      `
          float dist = distance(vUv, center);
          float alpha = smoothstep(radius, radius - 0.5, dist);
          vec3 color = mix(color1, color2, alpha);
          gl_FragColor = vec4(color, opacitys);
      `,
    )
  }

  gradientPlaneMesh = new THREE.Mesh(new THREE.PlaneGeometry(2400, 2400), gradientMaterial)
  gradientPlaneMesh.renderOrder = -5
  gradientPlaneMesh.position.set(0, -11.2, -8)
  gradientPlaneMesh.rotateX(-Math.PI / 2)
  group.add(gradientPlaneMesh)

  backgroundFxGroup = group
  scene.add(group)
  syncBackgroundFxLayout()
}

function syncBackgroundFxLayout() {
  const wrappedSpan = Math.max(160, currentLayerSpan * Math.max(1, mapScaleFactor))
  const isCountryLayer = currentLayerLevel === 'province'

  // Match the map demo: country outer ring should tightly wrap map bounds.
  const innerSize = wrappedSpan * (isCountryLayer ? 0.8 : 1.04)
  const outerSize = wrappedSpan * (isCountryLayer ? 1.05 : 1.24)

  if (innerRingMesh) {
    const scale = innerSize / BACKGROUND_RING_BASE_INNER
    innerRingMesh.scale.set(scale, scale, 1)
    innerRingMesh.position.set(0, -11, 0)
  }

  if (outerRingMesh) {
    const scale = outerSize / BACKGROUND_RING_BASE_OUTER
    outerRingMesh.scale.set(scale, scale, 1)
    outerRingMesh.position.set(0, -11, 0)
  }
}

function tickBackgroundFx(deltaTime: number) {
  mapUf.uTime.value += deltaTime
  if (mapUf.uTime.value >= 6) {
    mapUf.uTime.value = 0
  }

  if (outerRingMesh) outerRingMesh.rotation.z -= 0.0026
  if (innerRingMesh) innerRingMesh.rotation.z += 0.0026
}

// Match the map project's animated side-wall gradient style using the same mapUf idea.
function createSideWallMaterial() {
  const material = new THREE.MeshBasicMaterial({
    color: '#1EB4E6',
    transparent: true,
    opacity: 1,
  })

  material.onBeforeCompile = (shader) => {
    shader.uniforms = {
      ...shader.uniforms,
      ...mapUf,
    }

    shader.vertexShader = shader.vertexShader.replace(
      'void main() {',
      `
          varying vec3 vPosition;
          void main() {
            vPosition = position;
      `,
    )

    shader.fragmentShader = shader.fragmentShader.replace(
      'void main() {',
      `
          varying vec3 vPosition;
          uniform float uTime;
          uniform float uHeight;
          uniform vec3 uColor;
          uniform float uStart;
          uniform float uSpeed;
          void main() {
      `,
    )

    shader.fragmentShader = shader.fragmentShader.replace(
      '#include <output_fragment>',
      `
          float h = clamp((vPosition.z - uStart) / max(uHeight, 0.0001), 0.0, 1.0);
          float pulse = sin((vPosition.z + uTime * uSpeed) * 0.85) * 0.5 + 0.5;
          vec3 gradColor = mix(uColor * 0.38, uColor, h);
          vec3 finalColor = gradColor + uColor * pulse * 0.2;
          gl_FragColor = vec4(finalColor, diffuseColor.a);
      `,
    )
  }

  return material
}

function stopAnimationLoop() {
  if (animationFrameId !== null) {
    cancelAnimationFrame(animationFrameId)
    animationFrameId = null
  }
}

function disposeObject3D(obj: THREE.Object3D | null) {
  if (!obj) return
  obj.traverse((item) => {
    const mesh = item as THREE.Mesh
    if (mesh.geometry) mesh.geometry.dispose()
    if (mesh.material) {
      if (Array.isArray(mesh.material)) {
        mesh.material.forEach(material => material.dispose())
      } else {
        mesh.material.dispose()
      }
    }
  })
  obj.clear()
}

function clearLabelTextures() {
  regionMetaByAdcode.forEach((meta) => {
    if (!meta.labelSprite) return
    const material = meta.labelSprite.material as THREE.SpriteMaterial
    material.map?.dispose()
    material.dispose()
  })
}

function cleanupScene() {
  stopAnimationLoop()
  resizeObserver?.disconnect()
  resizeObserver = null

  if (renderer?.domElement) {
    renderer.domElement.removeEventListener('click', onCanvasClick)
    renderer.domElement.removeEventListener('dblclick', onCanvasDoubleClick)
    renderer.domElement.removeEventListener('mousemove', onCanvasMouseMove)
  }

  controls?.dispose()
  controls = null

  clearLabelTextures()

  disposeObject3D(regionGroup)
  disposeObject3D(prismGroup)
  disposeObject3D(flyLineGroup)
  disposeObject3D(backgroundFxGroup)
  disposeObject3D(mapRoot)

  regionGroup = null
  prismGroup = null
  flyLineGroup = null
  backgroundFxGroup = null
  outerRingMesh = null
  innerRingMesh = null
  gradientPlaneMesh = null
  mapRoot = null
  mapScaleFactor = 1
  countryReferenceSpan = 0
  animationClock = null

  renderer?.dispose()
  if (renderer?.domElement?.parentNode) {
    renderer.domElement.parentNode.removeChild(renderer.domElement)
  }

  scene = null
  camera = null
  renderer = null
  raycaster = null
  pickableMeshes = []
  regionMetaByAdcode = new Map<number, RegionMeta>()
  flyLineState = []
  hoveredRegion = null
  selectedRegion = null
  setPopupVisible(false)
  mapError.value = ''
}

function resizeRenderer() {
  if (!containerRef.value || !renderer || !camera) return
  const width = containerRef.value.clientWidth
  const height = containerRef.value.clientHeight
  if (!width || !height) return

  renderer.setSize(width, height)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  camera.aspect = width / height
  camera.updateProjectionMatrix()
}

function animate() {
  if (!renderer || !scene || !camera || !controls) return
  animationFrameId = requestAnimationFrame(animate)
  const deltaTime = animationClock?.getDelta() ?? 0.016
  controls.update()
  tickBackgroundFx(deltaTime)
  tickRegionLiftAnimation()
  tickFlyLineAnimation()
  renderer.render(scene, camera)
  updatePopupPosition()
}

function initScene() {
  if (!containerRef.value) return

  animationClock = new THREE.Clock()

  scene = new THREE.Scene()
  scene.background = new THREE.Color('#051327')
  scene.fog = new THREE.FogExp2('#061932', 0.0012)

  const width = containerRef.value.clientWidth
  const height = containerRef.value.clientHeight

  camera = new THREE.PerspectiveCamera(33, width / height, 1, 5000)
  camera.position.set(0, 320, 230)

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setSize(width, height)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))

  containerRef.value.innerHTML = ''
  containerRef.value.appendChild(renderer.domElement)

  const ambient = new THREE.AmbientLight(0xffffff, 0.84)
  const keyLight = new THREE.DirectionalLight(0x9ad9ff, 1.0)
  keyLight.position.set(220, 320, 300)
  const fill = new THREE.DirectionalLight(0x22d3ee, 0.3)
  fill.position.set(-260, 180, -180)
  scene.add(ambient, keyLight, fill)

  const grid = new THREE.GridHelper(760, 24, '#1e40af', '#102241')
  grid.position.y = -10
  scene.add(grid)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.minDistance = 120
  controls.maxDistance = 1100
  controls.minPolarAngle = 0.36
  controls.maxPolarAngle = Math.PI / 2.08

  mapRoot = new THREE.Group()
  mapRoot.rotateX(-Math.PI / 2)
  mapRoot.name = 'mapRoot'
  mapRoot.renderOrder = 20

  regionGroup = new THREE.Group()
  prismGroup = new THREE.Group()
  flyLineGroup = new THREE.Group()
  prismGroup.position.z = 0.12
  flyLineGroup.renderOrder = 30

  mapRoot.add(regionGroup, prismGroup, flyLineGroup)
  scene.add(mapRoot)
  void createBackgroundFxLayer()

  raycaster = new THREE.Raycaster()

  renderer.domElement.addEventListener('click', onCanvasClick)
  renderer.domElement.addEventListener('dblclick', onCanvasDoubleClick)
  renderer.domElement.addEventListener('mousemove', onCanvasMouseMove)

  resizeObserver = new ResizeObserver(() => resizeRenderer())
  resizeObserver.observe(containerRef.value)

  animate()
}

function mercatorY(lat: number) {
  const rad = (lat * Math.PI) / 180
  return Math.log(Math.tan(Math.PI / 4 + rad / 2))
}

function fitProjection(features: GeoFeature[]) {
  // Keep the same projection baseline as the provided full-map implementation
  // so line, mesh and texture UV coordinates stay in one coordinate system.
  void features
  projectionCenterLon = BASE_PROJECTION_CENTER_LON
  projectionCenterLat = BASE_PROJECTION_CENTER_LAT
  projectionCenterMercatorY = mercatorY(projectionCenterLat)
  projectionScale = BASE_PROJECTION_SCALE
}

function projectPoint(lonLat: [number, number]): [number, number] {
  const x = (((Number(lonLat[0]) - projectionCenterLon) * Math.PI) / 180) * projectionScale
  const y = (projectionCenterMercatorY - mercatorY(Number(lonLat[1]))) * projectionScale
  return [x, y]
}

function buildShape(ring: any[]): THREE.Shape {
  const shape = new THREE.Shape()
  ring.forEach((point: any[], index: number) => {
    const [x, y] = projectPoint([Number(point[0]), Number(point[1])])
    if (index === 0) shape.moveTo(x, y)
    else shape.lineTo(x, y)
  })
  return shape
}

function updateProjectedRingBounds(
  ring: any[],
  bounds: { minX: number; maxX: number; minY: number; maxY: number },
) {
  ring.forEach((point: any[]) => {
    const [x, y] = projectPoint([Number(point[0]), Number(point[1])])
    const py = -y
    if (x < bounds.minX) bounds.minX = x
    if (x > bounds.maxX) bounds.maxX = x
    if (py < bounds.minY) bounds.minY = py
    if (py > bounds.maxY) bounds.maxY = py
  })
}

function createBoundaryLine(ring: any[]): THREE.Line {
  const points = ring.map((point: any[]) => {
    const [x, y] = projectPoint([Number(point[0]), Number(point[1])])
    return new THREE.Vector3(x, -y, 0)
  })
  const geometry = new THREE.BufferGeometry().setFromPoints(points)
  const material = new THREE.LineBasicMaterial({
    color: '#cde0ff',
    transparent: true,
    opacity: 0.56,
  })
  const line = new THREE.Line(geometry, material)
  line.position.z = 0.2
  return line
}

function resolveRegionColor(jobCount: number, maxCount: number): THREE.Color {
  if (jobCount <= 0) {
    return new THREE.Color().setHSL(0.58, 0.42, 0.23)
  }
  const ratioRaw = maxCount > 0 ? Math.min(1, jobCount / maxCount) : 0
  const ratio = Math.pow(ratioRaw, 0.45)
  const hue = 0.56 - ratio * 0.14
  const sat = 0.72 + ratio * 0.14
  const lightness = 0.42 + ratio * 0.2
  return new THREE.Color().setHSL(hue, sat, lightness)
}

function createLabelSprite(name: string) {
  const canvas = document.createElement('canvas')
  canvas.width = 256
  canvas.height = 80
  const ctx = canvas.getContext('2d')
  if (!ctx) return null

  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = 'rgba(3, 16, 36, 0.72)'
  ctx.strokeStyle = 'rgba(148, 196, 255, 0.7)'
  ctx.lineWidth = 2

  const x = 10
  const y = 10
  const w = canvas.width - 20
  const h = canvas.height - 20
  const radius = 12

  ctx.beginPath()
  ctx.moveTo(x + radius, y)
  ctx.lineTo(x + w - radius, y)
  ctx.quadraticCurveTo(x + w, y, x + w, y + radius)
  ctx.lineTo(x + w, y + h - radius)
  ctx.quadraticCurveTo(x + w, y + h, x + w - radius, y + h)
  ctx.lineTo(x + radius, y + h)
  ctx.quadraticCurveTo(x, y + h, x, y + h - radius)
  ctx.lineTo(x, y + radius)
  ctx.quadraticCurveTo(x, y, x + radius, y)
  ctx.closePath()
  ctx.fill()
  ctx.stroke()

  ctx.fillStyle = '#e2f0ff'
  ctx.font = '600 28px "Microsoft YaHei"'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(name, canvas.width / 2, canvas.height / 2)

  const texture = new THREE.CanvasTexture(canvas)
  texture.needsUpdate = true

  const material = new THREE.SpriteMaterial({
    map: texture,
    transparent: true,
    depthWrite: false,
    depthTest: false,
  })
  material.toneMapped = false

  const sprite = new THREE.Sprite(material)
  sprite.scale.set(16, 5, 1)
  sprite.renderOrder = 260
  sprite.visible = false
  return sprite
}

function syncLabelScaleByLayer() {
  const isCityLayer = currentLayerLevel === 'city'
  const baseWidth = isCityLayer ? 13.5 : 16
  const baseHeight = isCityLayer ? 4.2 : 5
  const inverseMapScale = 1 / Math.max(1, mapScaleFactor)
  const layerAdjust = isCityLayer ? 1.35 : 1
  const factor = THREE.MathUtils.clamp(inverseMapScale * layerAdjust, 0.5, 1.08)

  regionMetaByAdcode.forEach((meta) => {
    if (!meta.labelSprite) return
    const baseZ = Number(meta.labelSprite.userData.baseZ || (isCityLayer ? 9.2 : 13.4))
    // Keep label world height stable when layer is scaled up.
    meta.labelSprite.position.z = baseZ / Math.max(1, mapScaleFactor)
    meta.labelSprite.scale.set(baseWidth * factor, baseHeight * factor, 1)
  })
}

function clearMapLayer() {
  if (!regionGroup || !prismGroup || !flyLineGroup) return

  clearLabelTextures()
  disposeObject3D(regionGroup)
  disposeObject3D(prismGroup)
  disposeObject3D(flyLineGroup)

  pickableMeshes = []
  regionMetaByAdcode = new Map<number, RegionMeta>()
  flyLineState = []
  hoveredRegion = null
  selectedRegion = null
  setPopupVisible(false)
}

function ensureMapSurfaceTexture(): Promise<THREE.Texture | null> {
  if (mapTexture) return Promise.resolve(mapTexture)
  if (mapTexturePromise) return mapTexturePromise

  mapTexturePromise = new Promise((resolve) => {
    const loader = new THREE.TextureLoader()
    loader.load(
      '/mapPublic/quanGuo.png',
      (texture) => {
        texture.flipY = false
        texture.wrapS = THREE.RepeatWrapping
        texture.wrapT = THREE.RepeatWrapping
        texture.repeat.set(0.00608, 0.00825)
        texture.offset.set(0.5448, 0.549)
        mapTexture = texture
        resolve(texture)
      },
      undefined,
      () => {
        resolve(null)
      },
    )
  })

  return mapTexturePromise
}

function applyTextureModeToMap() {
  regionMetaByAdcode.forEach((meta) => {
    if (effects.texture) {
      const heatColor = meta.baseColor.clone()
        .multiplyScalar(0.56)
        .lerp(new THREE.Color('#052d3a'), 0.58)
      meta.topMaterial.map = null
      meta.topMaterial.color = heatColor
      meta.topMaterial.emissive = heatColor.clone().multiplyScalar(0.025)
      meta.topMaterial.emissiveIntensity = 0.1
      meta.topMaterial.metalness = 0.06
      meta.topMaterial.roughness = 0.78
      meta.topMaterial.needsUpdate = true
      return
    }

    if (mapTexture) {
      meta.topMaterial.map = mapTexture
      meta.topMaterial.color = new THREE.Color('#7eaab6')
      meta.topMaterial.emissive = new THREE.Color('#0b2229')
      meta.topMaterial.emissiveIntensity = 0.08
      meta.topMaterial.metalness = 0.12
      meta.topMaterial.roughness = 0.7
      meta.topMaterial.needsUpdate = true
      return
    }

    meta.topMaterial.map = null
    meta.topMaterial.color = meta.baseColor.clone()
    meta.topMaterial.metalness = 0.12
    meta.topMaterial.roughness = 0.62
    meta.topMaterial.needsUpdate = true
  })
}

async function applyTextureMode() {
  if (!effects.texture) {
    const texture = await ensureMapSurfaceTexture()
    if (!texture && !textureFailureNotified) {
      textureFailureNotified = true
      ElMessage.warning('贴图资源加载失败，已自动切换为地区热度着色')
      effects.texture = true
      return
    }
  }
  applyTextureModeToMap()
}

function attachRegionStatPrisms(statByAdcode: Map<number, RegionStat>) {
  if (!prismGroup) return
  const currentPrismGroup = prismGroup
  disposeObject3D(currentPrismGroup)

  const values = [...statByAdcode.values()].map(item => Number(item.jobCount || 0))
  const maxCount = Math.max(1, ...values)
  const sortedPositiveCounts = values.filter(item => item > 0).sort((a, b) => b - a)
  const redCount = Math.max(1, Math.round(sortedPositiveCounts.length * 0.16))
  const yellowCount = Math.max(redCount + 1, Math.round(sortedPositiveCounts.length * 0.48))

  const colorDefault = new THREE.Color('#1E90FF')
  const colorYellow = new THREE.Color('#FFD447')
  const colorRed = new THREE.Color('#FF4D4F')

  const spanScale = THREE.MathUtils.clamp(currentLayerSpan / 220, 0.45, 1.2)
  const normalizeByMapScale = currentLayerLevel === 'city'
    ? 1 / Math.max(1, Math.sqrt(mapScaleFactor) * 0.96)
    : 1 / Math.max(1, mapScaleFactor * 0.82)
  const layerScale = (currentLayerLevel === 'city' ? spanScale * 0.8 : spanScale) * normalizeByMapScale
  const radius = Math.max(0.22, (currentLayerLevel === 'city' ? 0.56 : 0.5) * layerScale)

  regionMetaByAdcode.forEach((meta) => {
    const stat = statByAdcode.get(meta.adcode)
    const count = Number(stat?.jobCount || 0)
    if (!count) return

    const ratio = Math.min(1, count / maxCount)
    const heightBase = currentLayerLevel === 'city' ? 2.2 : 1.8
    const heightAmp = currentLayerLevel === 'city' ? 14.6 : 12.5
    const height = (heightBase + Math.pow(ratio, 0.5) * heightAmp) * layerScale

    const rankIndex = sortedPositiveCounts.findIndex(value => count >= value)
    const rank = rankIndex < 0 ? sortedPositiveCounts.length - 1 : rankIndex
    let color = colorDefault
    if (rank < redCount) color = colorRed
    else if (rank < yellowCount) color = colorYellow

    const geometry = new THREE.CylinderGeometry(radius, radius, height, 6)
    geometry.rotateX(Math.PI / 2)

    const material = new THREE.MeshBasicMaterial({
      color,
    })

    const prism = new THREE.Mesh(geometry, material)
    prism.position.set(meta.center[0], meta.center[1], height / 2 + 0.18)
    prism.name = '棱柱'
    currentPrismGroup.add(prism)
  })
}

function toFamilyVector(stat: RegionStat) {
  const vector = new Map<string, number>()
  const distribution = Array.isArray((stat as any)?.jobFamilyDistribution) ? (stat as any).jobFamilyDistribution : []
  distribution.forEach((item: any) => {
    const key = String(item?.name || '').trim()
    const count = Number(item?.count || 0)
    if (!key || count <= 0) return
    vector.set(key, count)
  })
  return vector
}

function cosineSimilarity(a: Map<string, number>, b: Map<string, number>) {
  if (!a.size || !b.size) return 0
  let dot = 0
  let normA = 0
  let normB = 0

  a.forEach((value, key) => {
    normA += value * value
    dot += value * Number(b.get(key) || 0)
  })
  b.forEach((value) => {
    normB += value * value
  })

  if (!normA || !normB) return 0
  return dot / Math.sqrt(normA * normB)
}

function buildSimilarityPairs(statByAdcode: Map<number, RegionStat>) {
  const candidates = [...statByAdcode.entries()]
    .map(([adcode, stat]) => ({
      adcode,
      stat,
      vector: toFamilyVector(stat),
      jobCount: Number(stat?.jobCount || 0),
    }))
    .filter(item => item.jobCount > 0 && item.vector.size > 0 && regionMetaByAdcode.has(item.adcode))

  const pairs: Array<{ sourceAdcode: number; targetAdcode: number; similarity: number }> = []

  for (let i = 0; i < candidates.length; i += 1) {
    for (let j = i + 1; j < candidates.length; j += 1) {
      const sim = cosineSimilarity(candidates[i].vector, candidates[j].vector)
      if (sim >= 0.74) {
        pairs.push({
          sourceAdcode: candidates[i].adcode,
          targetAdcode: candidates[j].adcode,
          similarity: Number(sim.toFixed(4)),
        })
      }
    }
  }

  pairs.sort((a, b) => b.similarity - a.similarity)

  const degree = new Map<number, number>()
  const selected: Array<{ sourceAdcode: number; targetAdcode: number; similarity: number }> = []
  for (const pair of pairs) {
    if (selected.length >= 12) break
    const leftDegree = Number(degree.get(pair.sourceAdcode) || 0)
    const rightDegree = Number(degree.get(pair.targetAdcode) || 0)
    if (leftDegree >= 3 || rightDegree >= 3) continue

    selected.push(pair)
    degree.set(pair.sourceAdcode, leftDegree + 1)
    degree.set(pair.targetAdcode, rightDegree + 1)
  }

  return selected
}

function resolveFlylinePalette(similarity: number) {
  if (similarity >= 0.88) {
    return {
      line: '#95f5bd',
      marker: '#d8ffe8',
      opacity: 0.9,
    }
  }
  if (similarity >= 0.8) {
    return {
      line: '#ffe8a3',
      marker: '#fff6d9',
      opacity: 0.8,
    }
  }
  return {
    line: '#8fd6ff',
    marker: '#dff3ff',
    opacity: 0.66,
  }
}

function attachFlyLinesBySimilarity(statByAdcode: Map<number, RegionStat>) {
  if (!flyLineGroup) return
  const currentFlyLineGroup = flyLineGroup
  disposeObject3D(currentFlyLineGroup)
  flyLineState = []
  const spanScale = THREE.MathUtils.clamp(currentLayerSpan / 220, 0.45, 1.2)
  const normalizeByMapScale = 1 / Math.max(1, mapScaleFactor * 0.78)
  const layerScale = (currentLayerLevel === 'city' ? spanScale * 0.72 : spanScale) * normalizeByMapScale

  const relationPairs = buildSimilarityPairs(statByAdcode)
  relationPairs.forEach((pair, index) => {
    const sourceMeta = regionMetaByAdcode.get(pair.sourceAdcode)
    const targetMeta = regionMetaByAdcode.get(pair.targetAdcode)
    if (!sourceMeta || !targetMeta) return

    const start = new THREE.Vector3(sourceMeta.center[0], sourceMeta.center[1], 0.72)
    const end = new THREE.Vector3(targetMeta.center[0], targetMeta.center[1], 0.72)
    const middle = start.clone().add(end).multiplyScalar(0.5)
    const distance = start.distanceTo(end)
    middle.z = (3.2 + Math.min(12.5, distance * 0.13) + pair.similarity * 1.25) * layerScale
    const palette = resolveFlylinePalette(pair.similarity)

    const curve = new THREE.CatmullRomCurve3([start, middle, end])
    const points = curve.getSpacedPoints(140)

    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(points),
      new THREE.LineBasicMaterial({
        color: palette.line,
        transparent: true,
        opacity: palette.opacity,
        // Keep flylines readable when prism and flyline are enabled together.
        depthWrite: false,
        depthTest: false,
      }),
    )
    line.renderOrder = 40
    currentFlyLineGroup.add(line)

    const marker = new THREE.Mesh(
      new THREE.SphereGeometry(Math.max(0.24, 0.62 * layerScale), 10, 10),
      new THREE.MeshBasicMaterial({
        color: palette.marker,
        depthWrite: false,
        depthTest: false,
      }),
    )
    marker.renderOrder = 41
    currentFlyLineGroup.add(marker)

    flyLineState.push({
      curve,
      marker,
      progress: (index * 0.14) % 1,
      speed: 0.0022 + Math.min(0.0015, (pair.similarity - 0.74) * 0.005),
    })
  })
}

function buildRegionLayer(
  features: GeoFeature[],
  regionLevel: 'province' | 'city',
  statByAdcode: Map<number, RegionStat>,
) {
  if (!regionGroup || !camera || !controls) return
  const currentRegionGroup = regionGroup

  if (mapRoot) {
    // Always measure and rebuild from unscaled baseline to avoid span drift
    // after returning from drilldown layers.
    mapRoot.scale.setScalar(1)
    mapRoot.updateMatrixWorld(true)
  }

  clearMapLayer()
  currentRegionGroup.position.set(0, 0, 0)
  if (prismGroup) prismGroup.position.set(0, 0, 0.12)
  if (flyLineGroup) flyLineGroup.position.set(0, 0, 0)
  fitProjection(features)

  const geometryBounds = {
    minX: Number.POSITIVE_INFINITY,
    maxX: Number.NEGATIVE_INFINITY,
    minY: Number.POSITIVE_INFINITY,
    maxY: Number.NEGATIVE_INFINITY,
  }

  const maxCount = Math.max(
    1,
    ...features.map((feature) => Number(statByAdcode.get(Number(feature.properties?.adcode || 0))?.jobCount || 0)),
  )

  features.forEach((feature) => {
    const adcode = Number(feature.properties?.adcode)
    const name = String(feature.properties?.name || '').trim()
    if (!name || !Number.isFinite(adcode)) return

    const stat = statByAdcode.get(adcode)
    const fillColor = resolveRegionColor(Number(stat?.jobCount || 0), maxCount)

    const shapeList: THREE.Shape[] = []
    const lineList: THREE.Line[] = []

    if (feature.geometry?.type === 'Polygon') {
      const polygon = feature.geometry.coordinates || []
      if (Array.isArray(polygon) && polygon[0]) {
        shapeList.push(buildShape(polygon[0]))
      }
      polygon.forEach((ring: any[]) => {
        updateProjectedRingBounds(ring, geometryBounds)
        lineList.push(createBoundaryLine(ring))
      })
    }

    if (feature.geometry?.type === 'MultiPolygon') {
      const polygons = feature.geometry.coordinates || []
      polygons.forEach((polygon: any[]) => {
        if (Array.isArray(polygon) && polygon[0]) {
          shapeList.push(buildShape(polygon[0]))
        }
        polygon.forEach((ring: any[]) => {
          updateProjectedRingBounds(ring, geometryBounds)
          lineList.push(createBoundaryLine(ring))
        })
      })
    }

    if (!shapeList.length) return

    const geometry = new THREE.ExtrudeGeometry(shapeList, {
      depth: 10,
      bevelEnabled: true,
      bevelSegments: 4,
      bevelThickness: 0.18,
      bevelSize: 0.02,
    })

    const topMaterial = new THREE.MeshStandardMaterial({
      color: fillColor,
      emissive: fillColor.clone().multiplyScalar(0.05),
      metalness: 0.12,
      roughness: 0.62,
    })
    const sideMaterial = createSideWallMaterial()

    const mesh = new THREE.Mesh(geometry, [topMaterial, sideMaterial])
    mesh.rotateX(Math.PI)

    const centerProjected = Array.isArray(feature.properties?.center)
      ? projectPoint([Number(feature.properties.center[0]), Number(feature.properties.center[1])])
      : [0, 0]
    const center: [number, number] = [Number(centerProjected[0]), Number(-centerProjected[1])]

    const container = new THREE.Object3D()
    container.userData.targetLift = 0
    container.add(mesh)
    lineList.forEach(line => container.add(line))

    const labelSprite = createLabelSprite(name)
    if (labelSprite) {
      labelSprite.userData.baseZ = regionLevel === 'city' ? 9.2 : 13.4
      labelSprite.position.set(center[0], center[1], Number(labelSprite.userData.baseZ))
      container.add(labelSprite)
    }

    currentRegionGroup.add(container)

    const meta: RegionMeta = {
      level: regionLevel,
      adcode,
      name,
      center,
      mesh,
      container,
      topMaterial,
      sideMaterial,
      baseColor: fillColor.clone(),
      labelSprite: labelSprite || undefined,
    }

    mesh.userData.regionMeta = meta
    regionMetaByAdcode.set(adcode, meta)
    pickableMeshes.push(mesh)
  })

  const box = new THREE.Box3().setFromObject(currentRegionGroup)
  const center = new THREE.Vector3()
  const size = new THREE.Vector3()
  box.getCenter(center)
  box.getSize(size)

  const boundsValid = Number.isFinite(geometryBounds.minX)
    && Number.isFinite(geometryBounds.maxX)
    && Number.isFinite(geometryBounds.minY)
    && Number.isFinite(geometryBounds.maxY)
  const centerX = boundsValid ? (geometryBounds.minX + geometryBounds.maxX) / 2 : center.x
  const centerY = boundsValid ? (geometryBounds.minY + geometryBounds.maxY) / 2 : center.y
  const spanX = boundsValid ? Math.max(1, geometryBounds.maxX - geometryBounds.minX) : Math.max(1, size.x)
  const spanY = boundsValid ? Math.max(1, geometryBounds.maxY - geometryBounds.minY) : Math.max(1, size.y)

  const useMapBaselineCenter = regionLevel === 'province'
  const offsetX = useMapBaselineCenter ? 0 : -centerX
  const offsetY = useMapBaselineCenter ? 0 : -centerY
  currentRegionGroup.position.set(offsetX, offsetY, 0)
  if (prismGroup) prismGroup.position.set(offsetX, offsetY, 0.12)
  if (flyLineGroup) flyLineGroup.position.set(offsetX, offsetY, 0)

  currentLayerLevel = regionLevel
  currentLayerSpan = Math.max(1, Math.max(spanX, spanY))
  if (regionLevel === 'province') {
    countryReferenceSpan = Math.max(1, (spanX + spanY) / 2)
    mapScaleFactor = 1
  } else {
    const currentBoxSize = Math.max(1, (spanX + spanY) / 1.4)
    mapScaleFactor = THREE.MathUtils.clamp(countryReferenceSpan / currentBoxSize, 1.4, 8.0)
  }
  if (mapRoot) mapRoot.scale.setScalar(mapScaleFactor)
  syncBackgroundFxLayout()

  attachRegionStatPrisms(statByAdcode)
  attachFlyLinesBySimilarity(statByAdcode)
  syncLabelScaleByLayer()

  if (regionLevel === 'province') {
    controls.target.set(-5, 0, 10)
  } else {
    controls.target.set(0, 0, 0)
  }
  controls.minDistance = regionLevel === 'city' ? 34 : 80
  controls.maxDistance = regionLevel === 'city' ? 1150 : 1200

  const maxSize = Math.max(currentLayerSpan * mapScaleFactor, regionLevel === 'province' ? 220 : 140)
  if (regionLevel === 'province') {
    camera.position.set(-5, maxSize * 1.08, maxSize * 0.68)
  } else {
    camera.position.set(0, maxSize * 1.1, maxSize * 0.86)
  }
  if (regionLevel === 'province') {
    camera.lookAt(-5, 0, 10)
  } else {
    camera.lookAt(0, 0, 0)
  }
  controls.update()

  syncEffectMode()
  applyEffectVisibility()
  void applyTextureMode()
  refreshLabelVisibility()
}

function applyEffectVisibility() {
  if (prismGroup) prismGroup.visible = effects.prism
  if (flyLineGroup) flyLineGroup.visible = effects.flyline
}

function refreshLabelVisibility() {
  const hideLabelsBecausePopupVisible = popup.visible && Boolean(selectedRegion)
  regionMetaByAdcode.forEach((meta) => {
    if (!meta.labelSprite) return
    if (hideLabelsBecausePopupVisible) {
      meta.labelSprite.visible = false
      return
    }
    if (effects.showAllLabels) {
      meta.labelSprite.visible = true
      return
    }

    const visibleByInteraction = hoveredRegion?.adcode === meta.adcode
    meta.labelSprite.visible = Boolean(visibleByInteraction)
  })
}

function setRegionHighlight(meta: RegionMeta | null, selected = false) {
  if (!meta) return
  if (selected) {
    meta.topMaterial.emissive = new THREE.Color('#dff8ff')
    meta.topMaterial.emissiveIntensity = 0.45
    return
  }
  meta.topMaterial.emissive = meta.baseColor.clone().multiplyScalar(0.08)
  meta.topMaterial.emissiveIntensity = 0.24
}

function applyRegionLiftTargets() {
  regionMetaByAdcode.forEach((meta) => {
    const isHovered = hoveredRegion?.adcode === meta.adcode
    const isSelected = selectedRegion?.adcode === meta.adcode
    meta.container.userData.targetLift = (isHovered ? 3.0 : 0) + (isSelected ? 2.0 : 0)
  })
}

function tickRegionLiftAnimation() {
  regionMetaByAdcode.forEach((meta) => {
    const target = Number(meta.container.userData.targetLift || 0)
    const current = meta.container.position.z
    const next = current + (target - current) * 0.18
    meta.container.position.z = Math.abs(next - target) < 0.01 ? target : next
  })
}

function tickFlyLineAnimation() {
  if (!effects.flyline || !flyLineState.length) return
  flyLineState.forEach((item) => {
    item.progress += item.speed
    if (item.progress > 1) item.progress -= 1
    const point = item.curve.getPoint(item.progress)
    item.marker.position.copy(point)
  })
}

function getRegionMetaFromMesh(mesh: THREE.Mesh | null) {
  if (!mesh) return null
  return (mesh.userData.regionMeta as RegionMeta | undefined) || null
}

function pickMesh(event: MouseEvent): THREE.Mesh | null {
  if (!renderer || !camera || !raycaster) return null
  const rect = renderer.domElement.getBoundingClientRect()
  pointer = new THREE.Vector2(
    ((event.clientX - rect.left) / rect.width) * 2 - 1,
    -((event.clientY - rect.top) / rect.height) * 2 + 1,
  )
  raycaster.setFromCamera(pointer, camera)
  const intersects = raycaster.intersectObjects(pickableMeshes, true)
  if (!intersects.length) return null
  return intersects[0].object as THREE.Mesh
}

function setHoveredRegion(meta: RegionMeta | null) {
  if (hoveredRegion?.adcode === meta?.adcode) return

  if (hoveredRegion && (!selectedRegion || selectedRegion.adcode !== hoveredRegion.adcode)) {
    setRegionHighlight(hoveredRegion, false)
  }

  hoveredRegion = meta

  if (hoveredRegion && (!selectedRegion || selectedRegion.adcode !== hoveredRegion.adcode)) {
    hoveredRegion.topMaterial.emissive = new THREE.Color('#b5f7ff')
    hoveredRegion.topMaterial.emissiveIntensity = 0.36
  }

  applyRegionLiftTargets()
  refreshLabelVisibility()
}

function setSelectedRegion(meta: RegionMeta | null) {
  if (selectedRegion && (!meta || selectedRegion.adcode !== meta.adcode)) {
    setRegionHighlight(selectedRegion, false)
  }

  selectedRegion = meta
  if (selectedRegion) setRegionHighlight(selectedRegion, true)

  applyRegionLiftTargets()
  refreshLabelVisibility()
}

function buildPopupAnchor(meta: RegionMeta) {
  const isCity = meta.level === 'city'
  const baseAnchorZ = isCity ? 8.6 : 13.5
  const baseWithScale = isCity ? baseAnchorZ / Math.max(1, mapScaleFactor) : baseAnchorZ
  const liftOffset = isCity
    ? Number(meta.container.position.z || 0) / Math.max(1, mapScaleFactor)
    : Number(meta.container.position.z || 0)
  const local = new THREE.Vector3(meta.center[0], meta.center[1], baseWithScale + liftOffset)
  return meta.container.localToWorld(local)
}

function updatePopupPosition() {
  if (!popup.visible || !selectedRegion || !camera || !renderer) return
  const anchor = buildPopupAnchor(selectedRegion)
  if (!anchor) return

  const projected = anchor.project(camera)
  const width = renderer.domElement.clientWidth
  const height = renderer.domElement.clientHeight
  popup.x = ((projected.x + 1) / 2) * width
  popup.y = ((-projected.y + 1) / 2) * height
}

async function openPopup(meta: RegionMeta) {
  const token = ++popupRequestToken

  let body: { code: number; msg: string; payload?: any }
  try {
    const response = await getJobMapPopup({
      level: meta.level === 'province' ? 'province' : 'city',
      adcode: meta.adcode,
      jobFamilies: filters.jobFamilies.length ? [...filters.jobFamilies] : undefined,
      jobLevel: filters.jobLevel || undefined,
      cityTier: filters.cityTier || undefined,
      keyword: filters.keyword.trim() || undefined,
    })
    body = unwrapApi<any>(response)
  } catch (error: any) {
    if (token !== popupRequestToken) return
    const status = Number(error?.response?.status || 0)
    if (status === 404) {
      showNoJobPopup(meta)
      return
    }
    throw error
  }

  if (token !== popupRequestToken) return

  if (!isSuccessCode(body.code)) {
    showNoJobPopup(meta)
    return
  }

  const data = body.payload || {}
  const hasJobs = Number(data?.jobCount || 0) > 0 || Number(data?.jdCount || 0) > 0

  popup.level = meta.level === 'city' ? 'city' : 'province'
  popup.adcode = Number(meta.adcode || 0)
  popup.jobCount = Number(data?.jdCount || data?.jobCount || 0)
  popup.title = String(data?.cityName || data?.provinceName || meta.name)
  hideRegionJobsPanel()
  if (!hasJobs) {
    popup.lines = ['该地区暂无目标岗位']
    setPopupVisible(true)
    return
  }

  popup.lines = [
    `岗位分类数：${Number(data?.jobCount || 0)}`,
    `具体岗位数量：${Number(data?.jdCount || 0)}`,
    `平均月薪：${formatSalary(data?.avgSalaryMonthly)}`,
    data?.tier ? `城市层级：${String(data.tier)}` : '',
    Array.isArray(data?.topJobs) && data.topJobs.length
      ? `热门岗位：${data.topJobs.slice(0, 3).map((item: any) => String(item?.name || '')).join('、')}`
      : '',
  ].filter(Boolean)
  setPopupVisible(true)
}

async function onCanvasClick(event: MouseEvent) {
  if (mapLoading.value) return
  const mesh = pickMesh(event)
  if (!mesh) {
    setSelectedRegion(null)
    setPopupVisible(false)
    return
  }

  const meta = getRegionMetaFromMesh(mesh)
  if (!meta) return

  setSelectedRegion(meta)
  try {
    await openPopup(meta)
  } catch (error: any) {
    showNoJobPopup(meta)
    ElMessage.warning(error?.message || '弹窗数据加载失败')
  }
}

async function drillDownToProvince(meta: RegionMeta) {
  if (meta.level !== 'province') return

  mapLoading.value = true
  mapError.value = ''
  setPopupVisible(false)

  try {
    const cache = cityLayerCache.get(meta.adcode)
    if (cache) {
      cityStatsMap = cache.cityStats
      buildRegionLayer(cache.features, 'city', cityStatsMap as Map<number, RegionStat>)
      mapLevel.value = 'province'
      currentProvince.value = { name: meta.name, adcode: meta.adcode }
      return
    }

    const geoRes = await getJobMapProvinceGeoJson(meta.adcode)
    const geoBody = unwrapApi<GeoJsonFeatureCollection>(geoRes)
    if (!isSuccessCode(geoBody.code)) {
      throw new Error(geoBody.msg || '省级地图加载失败')
    }

    let cityList: JobMapCityStat[] = []
    try {
      const cityRes = await getJobMapCityStats(meta.adcode, toMapQuery())
      const cityBody = unwrapApi<{ list: JobMapCityStat[] }>(cityRes)
      if (isSuccessCode(cityBody.code)) {
        cityList = Array.isArray(cityBody.payload?.list) ? cityBody.payload!.list : []
      }
    } catch (error: any) {
      if (Number(error?.response?.status || 0) !== 404) {
        throw error
      }
      cityList = []
    }

    const geoData = (geoBody.payload || { features: [] }) as GeoJsonFeatureCollection
    const features = Array.isArray(geoData.features) ? (geoData.features as GeoFeature[]) : []

    cityStatsMap = new Map<number, JobMapCityStat>()
    cityList.forEach((item) => {
      cityStatsMap.set(Number(item.cityAdcode), item)
    })

    cityLayerCache.set(meta.adcode, {
      features,
      cityStats: cityStatsMap,
    })

    buildRegionLayer(features, 'city', cityStatsMap as Map<number, RegionStat>)
    mapLevel.value = 'province'
    currentProvince.value = { name: meta.name, adcode: meta.adcode }

    if (!cityList.length) {
      ElMessage.info('该地区暂无目标岗位')
    }
  } catch (error: any) {
    mapError.value = error?.message || '省级地图加载失败'
  } finally {
    mapLoading.value = false
  }
}

async function onCanvasDoubleClick(event: MouseEvent) {
  if (mapLoading.value) return
  if (mapLevel.value !== 'country') return
  const mesh = pickMesh(event)
  if (!mesh) return

  const meta = getRegionMetaFromMesh(mesh)
  if (!meta) return
  await drillDownToProvince(meta)
}

function onCanvasMouseMove(event: MouseEvent) {
  if (!renderer) return
  const mesh = pickMesh(event)
  const meta = getRegionMetaFromMesh(mesh)
  renderer.domElement.style.cursor = meta ? 'pointer' : 'default'
  setHoveredRegion(meta)
}

async function loadFilterOptions() {
  const response = await getJobMapFilters()
  const body = unwrapApi<JobMapFilterOptions>(response)
  if (!isSuccessCode(body.code)) {
    throw new Error(body.msg || '筛选项加载失败')
  }

  const data = (body.payload || {}) as Partial<JobMapFilterOptions>
  filterOptions.jobFamilies = Array.isArray(data?.jobFamilies) ? data.jobFamilies : []
  filterOptions.levels = Array.from(new Set(
    (Array.isArray(data?.levels) ? data.levels : [])
      .map(item => normalizeJobLevel(String(item || '')))
      .filter(Boolean),
  ))
  filterOptions.cityTiers = Array.isArray(data?.cityTiers) ? data.cityTiers : []
  filterOptions.provinces = Array.isArray(data?.provinces) ? data.provinces : []
}

async function loadCountryGeoJsonFromLocal() {
  const localCandidates = ['/mapPublic/newMap.json', '/mapPublic/map.json']
  for (const path of localCandidates) {
    try {
      const res = await fetch(path)
      if (!res.ok) continue
      const data = await res.json()
      if (Array.isArray(data?.features) && data.features.length > 0) {
        return data as GeoJsonFeatureCollection
      }
    } catch {
      // keep trying fallback sources
    }
  }

  throw new Error('本地地图资源缺失：请检查 public/mapPublic/newMap.json')
}

async function loadCountryLayer(options: { withProgress?: boolean } = {}) {
  mapLoading.value = true
  mapError.value = ''
  setPopupVisible(false)

  try {
    if (options.withProgress) updateOpenProgress('读取本地地图模型...', 46)
    const geoData = await loadCountryGeoJsonFromLocal()

    if (options.withProgress) updateOpenProgress('读取岗位聚合数据...', 68)
    const statRes = await getJobMapProvinceStats(toMapQuery())
    const statBody = unwrapApi<{ list: JobMapProvinceStat[] }>(statRes)
    if (!isSuccessCode(statBody.code)) {
      throw new Error(statBody.msg || '省级聚合数据加载失败')
    }

    const features = Array.isArray(geoData.features) ? (geoData.features as GeoFeature[]) : []
    const provinceList = Array.isArray(statBody.payload?.list) ? statBody.payload.list : []

    provinceStatsMap = new Map<number, JobMapProvinceStat>()
    provinceList.forEach((item) => {
      provinceStatsMap.set(Number(item.provinceAdcode), item)
    })

    if (options.withProgress) updateOpenProgress('构建三维地图...', 88)
    buildRegionLayer(features, 'province', provinceStatsMap as Map<number, RegionStat>)

    mapLevel.value = 'country'
    currentProvince.value = null

    if (options.withProgress) finishOpenProgress()
  } catch (error: any) {
    mapError.value = error?.message || '全国地图加载失败'
    if (options.withProgress) {
      loadProgress.visible = false
    }
  } finally {
    mapLoading.value = false
  }
}

async function refreshCurrentLayer() {
  if (mapLevel.value === 'country') {
    cityLayerCache.clear()
    await loadCountryLayer()
    return
  }

  if (!currentProvince.value) {
    await loadCountryLayer()
    return
  }

  cityLayerCache.delete(currentProvince.value.adcode)
  await drillDownToProvince({
    level: 'province',
    adcode: currentProvince.value.adcode,
    name: currentProvince.value.name,
    center: [0, 0],
    mesh: new THREE.Mesh(),
    container: new THREE.Object3D(),
    topMaterial: new THREE.MeshStandardMaterial(),
    sideMaterial: new THREE.MeshBasicMaterial(),
    baseColor: new THREE.Color('#1d4ed8'),
  })
}

async function applyMapFilters() {
  await refreshCurrentLayer()
}

async function backToCountry() {
  await loadCountryLayer()
}

watch(
  () => effects.showAllLabels,
  () => {
    refreshLabelVisibility()
  },
)

watch(
  () => [effects.prism, effects.flyline],
  () => {
    syncEffectMode()
    applyEffectVisibility()
  },
)

watch(
  () => effects.texture,
  () => {
    void applyTextureMode()
  },
)

watch(
  () => visible.value,
  async (open) => {
    if (open) {
      beginOpenProgress('初始化地图场景...', 6)
      const containerReady = await waitForContainerReady()
      if (!containerReady) {
        loadProgress.visible = false
        mapError.value = '地图容器初始化失败，请重试打开弹窗'
        return
      }
      initScene()

      try {
        updateOpenProgress('加载筛选项...', 24)
        await loadFilterOptions()
      } catch (error: any) {
        ElMessage.warning(error?.message || '筛选项加载失败')
      }

      await loadCountryLayer({ withProgress: true })
      return
    }

    loadProgress.visible = false
    cleanupScene()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (progressHideTimer) {
    clearTimeout(progressHideTimer)
    progressHideTimer = null
  }
  cleanupScene()
})
</script>

<template>
  <el-dialog
    v-model="visible"
    title="岗位地图探索"
    width="96vw"
    top="2vh"
    :close-on-click-modal="true"
    :destroy-on-close="true"
    append-to-body
  >
    <div class="map-dialog-panel">
      <div class="map-toolbar">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="输入关键词（可选）"
          class="toolbar-item"
        />
        <el-select v-model="filters.jobFamilies" multiple collapse-tags collapse-tags-tooltip filterable placeholder="岗位族（可多选）" class="toolbar-item">
          <el-option v-for="item in filterOptions.jobFamilies" :key="item" :label="item" :value="item" />
        </el-select>
        <el-select v-model="filters.jobLevel" clearable filterable placeholder="岗位级别" class="toolbar-item">
          <el-option v-for="item in filterOptions.levels" :key="item" :label="toJobLevelLabel(item)" :value="item" />
        </el-select>
        <el-select v-model="filters.cityTier" clearable placeholder="城市层级" class="toolbar-item">
          <el-option
            v-for="item in filterOptions.cityTiers"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>

        <div class="toolbar-actions">
          <el-button type="primary" :loading="mapLoading" :icon="Search" @click="applyMapFilters">筛选</el-button>
          <el-button @click="resetFilters">重置</el-button>
          <el-button v-if="mapLevel === 'province'" @click="backToCountry">返回全国</el-button>
        </div>
      </div>

      <div class="map-stage-wrapper">
        <div ref="containerRef" class="map-stage" />

        <div v-if="mapLoading" class="map-status">
          <el-tag type="info" effect="dark">地图更新中...</el-tag>
        </div>

        <div class="map-tip">地图来源于阿里云DataV“地理数据”模块。默认加载全国全部岗位数据，双击省份进入城市层。</div>

        <div class="effect-panel">
          <div class="effect-title">效果开关</div>
          <div class="effect-switches">
            <el-switch v-model="effects.showAllLabels" active-text="显示地名" />
            <el-switch v-model="effects.prism"  active-text="棱柱" />
            <el-switch v-model="effects.flyline"  active-text="飞线" />
            <el-switch v-model="effects.texture" active-text="地区热度" />
          </div>
        </div>

        <div v-if="loadProgress.visible" class="map-progress-mask">
          <div class="map-progress-card">
            <div class="progress-title">{{ loadProgress.text }}</div>
            <el-progress :percentage="loadProgress.percent" :stroke-width="16" />
          </div>
        </div>

        <div v-if="mapError" class="map-error">
          <el-alert :title="mapError" type="error" :closable="false" />
        </div>

        <div v-if="popup.visible" class="map-popup" :style="{ left: `${popup.x}px`, top: `${popup.y}px` }" @click.stop>
          <div class="popup-header">
            <div class="popup-title">{{ popup.title }}</div>
            <el-button
              text
              size="small"
              class="popup-detail-btn"
              :disabled="!popup.jobCount"
              :loading="regionJobs.loading"
              @click.stop="toggleRegionJobsPanel"
            >
              {{ regionJobs.visible ? '收起' : '详情' }}
            </el-button>
          </div>
          <div v-for="line in popup.lines" :key="line" class="popup-line">{{ line }}</div>
        </div>

        <div
          v-if="regionJobs.visible"
          class="map-popup-list"
          :style="{ left: `${popup.x + 174}px`, top: `${popup.y - 8}px` }"
          @click.stop
        >
          <div class="popup-list-title">{{ regionJobs.title || popup.title }} · 岗位详情（{{ filteredRegionJobs.length }}/{{ regionJobs.total }}）</div>
          <el-input
            v-model="regionJobsKeyword"
            clearable
            size="small"
            placeholder="在已加载列表中搜索岗位/公司"
            class="popup-list-search"
          />
          <el-scrollbar height="280px">
            <button
              v-for="item in filteredRegionJobs"
              :key="item.jobId"
              type="button"
              class="popup-job-item"
              @click.stop="handleRegionJobSelect(item)"
            >
              <div class="popup-job-title">{{ item.jobName }}</div>
              <div class="popup-job-meta">{{ item.companyName }} · {{ item.city }}{{ item.district ? `-${item.district}` : '' }}</div>
              <div class="popup-job-meta">{{ item.salaryNegotiable ? '面谈' : (item.salaryNormalized || '薪资待补充') }} · {{ formatDateYmd(item.updatedAtNormalized) }}</div>
            </button>
            <el-empty v-if="!filteredRegionJobs.length" description="未命中本地筛选结果" :image-size="42" />
          </el-scrollbar>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.map-dialog-panel {
  display: grid;
  gap: 12px;
}

.map-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.toolbar-item {
  width: 220px;
  max-width: 100%;
}

.toolbar-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

.map-stage-wrapper {
  position: relative;
  border: 1px solid #bfdbfe;
  border-radius: 14px;
  overflow: hidden;
  background: radial-gradient(circle at 18% 18%, #0b2b56 0%, #081a34 48%, #050f21 100%);
  height: 80vh;
  min-height: 620px;
}

.map-stage {
  width: 100%;
  height: 100%;
}

.map-status {
  position: absolute;
  right: 12px;
  top: 10px;
  z-index: 9;
}

.map-tip {
  position: absolute;
  left: 12px;
  top: 10px;
  z-index: 9;
  color: #e2e8f0;
  font-size: 12px;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.58);
  border: 1px solid rgba(148, 163, 184, 0.35);
}

.effect-panel {
  position: absolute;
  right: 12px;
  top: 46px;
  z-index: 9;
  width: 220px;
  border-radius: 10px;
  border: 1px solid rgba(148, 163, 184, 0.4);
  background: rgba(2, 17, 37, 0.78);
  backdrop-filter: blur(6px);
  padding: 10px;
  color: #dbeafe;
}

.effect-title {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 8px;
}

.effect-actions {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}

.effect-switches {
  display: grid;
  gap: 6px;
}

:deep(.effect-switches .el-switch) {
  --el-switch-on-color: #0284c7;
  --el-switch-off-color: #334155;
}

:deep(.effect-switches .el-switch__label) {
  color: #cbd5e1;
  font-size: 12px;
}

.map-progress-mask {
  position: absolute;
  inset: 0;
  z-index: 12;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(2, 8, 20, 0.42);
}

.map-progress-card {
  width: min(520px, calc(100% - 64px));
  border-radius: 14px;
  border: 1px solid rgba(147, 197, 253, 0.55);
  background: rgba(15, 23, 42, 0.82);
  padding: 14px 16px;
}

.progress-title {
  margin-bottom: 10px;
  color: #dbeafe;
  font-size: 13px;
}

.map-error {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 12px;
  z-index: 9;
}

.map-popup {
  position: absolute;
  z-index: 11;
  width: 306px;
  max-width: 306px;
  transform: translate(-50%, calc(-100% - 12px));
  border-radius: 10px;
  border: 1px solid #bfdbfe;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.28);
  padding: 10px 12px;
  pointer-events: auto;
}

.popup-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.popup-detail-btn {
  font-size: 12px;
}

.map-popup-list {
  position: absolute;
  z-index: 12;
  width: 320px;
  max-width: 320px;
  transform: translate(0, calc(-100% + 20px));
  border-radius: 10px;
  border: 1px solid #bfdbfe;
  background: rgba(255, 255, 255, 0.98);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.28);
  padding: 10px 12px;
}

.popup-list-title {
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 8px;
}

.popup-list-search {
  margin-bottom: 8px;
}

.popup-job-item {
  width: 100%;
  text-align: left;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  padding: 8px;
  margin-bottom: 8px;
  transition: border-color 0.2s ease, background-color 0.2s ease;
}

.popup-job-item:last-child {
  margin-bottom: 0;
}

.popup-job-item:hover {
  border-color: #60a5fa;
  background: #eff6ff;
}

.popup-job-title {
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 4px;
}

.popup-job-meta {
  font-size: 12px;
  color: #475569;
  line-height: 1.45;
}

.popup-title {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 6px;
}

.popup-line {
  font-size: 12px;
  color: #334155;
  line-height: 1.55;
}

@media (max-width: 1024px) {
  .toolbar-actions {
    margin-left: 0;
  }

  .toolbar-item {
    width: 100%;
  }

  .map-stage-wrapper {
    min-height: 500px;
    height: 74vh;
  }

  .effect-panel {
    width: 198px;
    right: 8px;
  }

  .map-popup {
    width: 246px;
    max-width: 246px;
  }

  .map-popup-list {
    width: 260px;
    max-width: 260px;
  }
}
</style>
