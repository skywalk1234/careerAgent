import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

// AI 生成的 markdown 正文统一渲染（CareerPlanView 内联了一份同款，这里供模拟面试相关页面共用）
// 与后端约定：html 一律关闭；宽松换行按 <br> 输出，便于 AI 自然换行排版
const renderer = new MarkdownIt({ html: false, linkify: true, breaks: true })
renderer.enable(['table', 'strikethrough'])
renderer.renderer.rules.table_open = () => '<div class="md-table-scroll"><table>'
renderer.renderer.rules.table_close = () => '</table></div>'

/** markdown 原文 → 已 DOMPurify 清洗的 HTML（配合全局 .markdown-body 样式使用） */
export function renderMarkdown(content: string | null | undefined): string {
  const text = String(content || '')
  if (!text.trim()) return ''
  return DOMPurify.sanitize(renderer.render(text))
}
