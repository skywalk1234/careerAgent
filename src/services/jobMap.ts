import { http } from './http'

export type JobMapCityTier = 'tier1' | 'tier2' | 'tier3'

export interface JobMapFilterOptions {
  jobFamilies: string[]
  levels: string[]
  cityTiers: Array<{ value: JobMapCityTier; label: string }>
  provinces: Array<{ name: string; adcode: number }>
}

export interface JobMapQuery {
  jobFamilies?: string[]
  jobFamily?: string
  level?: string
  cityTier?: JobMapCityTier | string
  keyword?: string
}

export interface JobMapProvinceStat {
  provinceName: string
  provinceAdcode: number
  jobCount: number
  jdCount: number
  cityCount: number
  avgSalaryMonthly: number | null
  topJobs: Array<{ name: string; count: number }>
  jobFamilyDistribution?: Array<{ name: string; count: number }>
  tierDistribution: Array<{ tier: string; count: number }>
}

export interface JobMapCityStat {
  cityName: string
  cityAdcode: number
  provinceName: string
  provinceAdcode: number
  jobCount: number
  jdCount: number
  avgSalaryMonthly: number | null
  tier: string
  topJobs: Array<{ name: string; count: number }>
  jobFamilyDistribution?: Array<{ name: string; count: number }>
}

export interface JobMapRegionJobItem {
  jobId: string
  jobName: string
  categoryName?: string
  jobCode?: string
  companyName: string
  city: string
  district?: string
  salaryNegotiable?: boolean
  salaryNormalized?: string
  level?: string
  updatedAtRaw?: string
  updatedAtNormalized?: string | null
  sourceSite?: string
  industryTags?: string[]
}

export interface JobMapRegionJobsResult {
  level: 'province' | 'city'
  adcode: number
  provinceAdcode?: number | null
  provinceName?: string
  cityAdcode?: number | null
  cityName?: string
  total: number
  page: number
  pageSize: number
  list: JobMapRegionJobItem[]
  updatedAt?: string
}

export interface GeoJsonFeatureCollection {
  type: 'FeatureCollection'
  features: Array<{
    type: 'Feature'
    properties: Record<string, any>
    geometry: {
      type: 'Polygon' | 'MultiPolygon'
      coordinates: any[]
    }
  }>
}

export function getJobMapFilters() {
  return http.get('/analytics/job-map/filters')
}

export function getJobMapProvinceStats(params: JobMapQuery = {}) {
  return http.get('/analytics/job-map/provinces', { params })
}

export function getJobMapCityStats(provinceAdcode: number | string, params: JobMapQuery = {}) {
  return http.get('/analytics/job-map/cities', {
    params: {
      ...params,
      provinceAdcode,
    },
  })
}

export function getJobMapPopup(params: {
  level: 'province' | 'city'
  adcode: number | string
  jobFamilies?: string[]
  jobFamily?: string
  jobLevel?: string
  cityTier?: JobMapCityTier | string
  keyword?: string
}) {
  return http.get('/analytics/job-map/popup', {
    params: {
      level: params.level,
      adcode: params.adcode,
      jobFamilies: params.jobFamilies,
      jobFamily: params.jobFamily,
      jobLevel: params.jobLevel,
      cityTier: params.cityTier,
      keyword: params.keyword,
    },
  })
}

export function getJobMapRegionJobs(params: {
  level: 'province' | 'city'
  adcode: number | string
  jobFamilies?: string[]
  jobFamily?: string
  jobLevel?: string
  cityTier?: JobMapCityTier | string
  keyword?: string
  page?: number
  pageSize?: number
}) {
  return http.get('/analytics/job-map/jobs', {
    params: {
      level: params.level,
      adcode: params.adcode,
      jobFamilies: params.jobFamilies,
      jobFamily: params.jobFamily,
      jobLevel: params.jobLevel,
      cityTier: params.cityTier,
      keyword: params.keyword,
      page: params.page,
      pageSize: params.pageSize,
    },
  })
}

export function getJobMapProvinceGeoJson(provinceAdcode: number | string) {
  return http.get(`/analytics/job-map/geojson/province/${encodeURIComponent(String(provinceAdcode))}`)
}
