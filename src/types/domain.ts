export type CompetencyKey =
  | 'professionalSkill'
  | 'certificate'
  | 'innovation'
  | 'internalMotivation'
  | 'learning'
  | 'stressTolerance'
  | 'communication'
  | 'internship'
  | 'language'
  | 'leadership'
  | 'adaptability'
  | 'execution'

export const ABILITY_DIMENSIONS: CompetencyKey[] = [
  'professionalSkill',
  'certificate',
  'innovation',
  'internalMotivation',
  'learning',
  'stressTolerance',
  'communication',
  'internship',
  'language',
  'leadership',
  'adaptability',
  'execution',
]

export const ABILITY_LABELS: Record<CompetencyKey, string> = {
  professionalSkill: '专业技能',
  certificate: '证书能力',
  innovation: '创新能力',
  internalMotivation: '内驱动力',
  learning: '学习能力',
  stressTolerance: '抗压能力',
  communication: '沟通能力',
  internship: '实习能力',
  language: '语言能力',
  leadership: '领导能力',
  adaptability: '适应能力',
  execution: '执行能力',
}

export const ABILITY_GROUPS: Array<{
  key: 'basicRequirement' | 'professionalLiteracy' | 'developmentPotential'
  label: string
  dimensions: CompetencyKey[]
}> = [
  {
    key: 'basicRequirement',
    label: '基础要求类',
    dimensions: ['learning', 'communication', 'adaptability', 'execution'],
  },
  {
    key: 'professionalLiteracy',
    label: '职业素养类',
    dimensions: ['professionalSkill', 'certificate', 'internship', 'language'],
  },
  {
    key: 'developmentPotential',
    label: '发展潜力类',
    dimensions: ['innovation', 'internalMotivation', 'stressTolerance', 'leadership'],
  },
]

export interface CompetencyScore {
  key: CompetencyKey
  label: string
  studentScore: number
  jobRequiredScore: number
  weight: number
}

export interface JobProfile {
  id: string
  jobTitle: string
  industryTags: string[]
  salaryNormalized?: string
  salaryNegotiable?: boolean
  city: string
  requirements: string[]
}

export interface StudentProfile {
  id: string
  name: string
  major: string
  targetRoles: string[]
  competencies: Array<{
    key: CompetencyKey
    score: number
  }>
}

export interface MatchResult {
  studentId: string
  jobId: string
  overallScore: number
  dimensionScores: {
    basicRequirement: number
    professionalSkill: number
    professionalLiteracy: number
    developmentPotential: number
  }
  competencyGap: CompetencyScore[]
}

export interface CareerActionPlan {
  stage: 'shortTerm' | 'midTerm'
  cycle: string
  goals: string[]
  tasks: string[]
  metrics: string[]
}
