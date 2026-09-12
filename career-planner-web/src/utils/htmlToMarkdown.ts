/**
 * 把 contenteditable（WYSIWYG）编辑后的 HTML 转回 markdown 全文。
 *
 * 「计划与行动方案」的编辑是直接改渲染后的正文（HTML），但后端正文始终以 markdown 存储
 * （CareerPlanController PUT /users/me/plans/{planId}），所以保存前用本工具把编辑结果
 * 还原成 markdown。覆盖 markdown-it（html:false + 表格）渲染产物 + 工具栏常用格式：
 * 标题 / 加粗 / 斜体 / 删除线 / 列表（含嵌套）/ 引用 / 代码块 / 表格 / 链接 / 分割线 / 段落。
 */

const TEXT_NODE = 3
const NBSP_RE = /[ ]/g

function isElementNode(node: Node): node is Element {
  return node.nodeType === 1
}

/** 合并空白与 nbsp，用于段落/单元格等普通文本；代码块/行内代码不走这里 */
function collapse(value: string): string {
  return value
    .replace(NBSP_RE, ' ')
    .replace(/[ \t]+/g, ' ')
    .replace(/\s*\n\s*/g, '\n')
    .trim()
}

/** 递归渲染行内内容，输出带 markdown 行内标记的字符串 */
function renderInlineChildren(node: Node): string {
  let out = ''
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeType === TEXT_NODE) {
      out += (child.nodeValue ?? '').replace(NBSP_RE, ' ')
      continue
    }
    if (!isElementNode(child)) continue

    const tag = child.tagName.toUpperCase()
    if (tag === 'BR') {
      out += '\n'
      continue
    }
    if (tag === 'IMG') {
      const alt = child.getAttribute('alt') || ''
      const src = child.getAttribute('src') || ''
      out += src ? `![${alt}](${src})` : alt
      continue
    }
    const inner = renderInlineChildren(child)
    if (tag === 'STRONG' || tag === 'B') out += inner ? `**${inner}**` : ''
    else if (tag === 'EM' || tag === 'I') out += inner ? `*${inner}*` : ''
    else if (tag === 'S' || tag === 'STRIKE' || tag === 'DEL') out += inner ? `~~${inner}~~` : ''
    else if (tag === 'CODE') out += `\`${child.textContent ?? ''}\``
    else if (tag === 'A') {
      const href = child.getAttribute('href')
      out += inner && href ? `[${inner}](${href})` : inner
    } else {
      // span / u / font / small / sub / sup …：只保留文本（去下划线等无效格式）
      out += inner
    }
  }
  return out
}

function renderInline(el: Element): string {
  return renderInlineChildren(el)
}

const BLOCK_TAGS = new Set([
  'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'P', 'UL', 'OL', 'BLOCKQUOTE', 'PRE', 'HR', 'TABLE', 'DIV', 'SECTION', 'ARTICLE', 'LI', 'TD', 'TH',
])

function isBlockTag(el: Element): boolean {
  return BLOCK_TAGS.has(el.tagName.toUpperCase())
}

/** 把容器内的块级孩子聚合成若干「块字符串」（块之间用空行分隔，块内自带换行） */
function collectBlockChunks(parent: Node): string[] {
  const chunks: string[] = []
  for (const child of Array.from(parent.childNodes)) {
    if (child.nodeType === TEXT_NODE) {
      const text = collapse(child.nodeValue ?? '')
      if (text) chunks.push(text)
      continue
    }
    if (!isElementNode(child)) continue
    if (isBlockTag(child)) {
      const chunk = blockToChunk(child)
      if (chunk) chunks.push(chunk)
    } else {
      const text = collapse(renderInline(child))
      if (text) chunks.push(text)
    }
  }
  return chunks
}

function blockToChunk(el: Element): string | null {
  const tag = el.tagName.toUpperCase()

  if (/^H[1-6]$/.test(tag)) {
    const text = collapse(renderInline(el))
    if (!text) return null
    const level = Number(tag.charAt(1))
    return `${'#'.repeat(level)} ${text}`
  }

  if (tag === 'P') {
    const text = collapse(renderInline(el))
    return text || null
  }

  if (tag === 'HR') {
    return '---'
  }

  if (tag === 'PRE') {
    const codeEl = el.querySelector('code') ?? el
    const codeText = (codeEl.textContent ?? '').replace(/^\n+/, '').replace(/\n+$/, '')
    const cls = typeof codeEl.className === 'string' ? codeEl.className : ''
    const langMatch = cls.match(/language-([\w+-]+)/)
    const lang = langMatch ? langMatch[1] : ''
    return codeText ? `\`\`\`${lang}\n${codeText}\n\`\`\`` : null
  }

  if (tag === 'BLOCKQUOTE') {
    const inner = collectBlockChunks(el)
    if (!inner.length) return null
    const lines = inner.flatMap((chunk) => chunk.split('\n').map((line) => (line ? `> ${line}` : '>')))
    return lines.join('\n')
  }

  if (tag === 'UL' || tag === 'OL') {
    return renderList(el, '') || null
  }

  if (tag === 'TABLE') {
    return tableToChunk(el)
  }

  // DIV / SECTION / ARTICLE 等容器：markdown-it 表格外层 div.md-table-scroll 也走这里递归
  const nested = collectBlockChunks(el)
  if (nested.length) return nested.join('\n\n')
  const text = collapse(renderInline(el))
  return text || null
}

/** 渲染 ul/ol（含嵌套），indent 为前缀空格 */
function renderList(listEl: Element, indent: string): string {
  const ordered = listEl.tagName.toUpperCase() === 'OL'
  const out: string[] = []
  let index = 1

  for (const li of Array.from(listEl.children)) {
    if (!isElementNode(li) || li.tagName.toUpperCase() !== 'LI') continue

    let first = ''
    const extras: string[] = []
    let nestedLines: string[] = []

    for (const child of Array.from(li.childNodes)) {
      if (child.nodeType === TEXT_NODE) {
        const text = collapse(child.nodeValue ?? '')
        if (!text) continue
        if (!first) first = text
        else extras.push(text)
        continue
      }
      if (!isElementNode(child)) continue
      const childTag = child.tagName.toUpperCase()
      if (childTag === 'UL' || childTag === 'OL') {
        const nested = renderList(child, `${indent}    `)
        if (nested) nestedLines = nested.split('\n')
        continue
      }
      if (childTag === 'LI') {
        // 防御：li 里嵌套 li（非法结构）按普通内容降级处理
        const text = collapse(renderInline(child))
        if (text) {
          if (!first) first = text
          else extras.push(text)
        }
        continue
      }
      if (isBlockTag(child)) {
        const chunk = blockToChunk(child)
        if (!chunk) continue
        const lines = chunk.split('\n')
        const head = collapse(lines[0])
        if (!first) first = head
        else extras.push(head)
        lines.slice(1).forEach((line) => line && extras.push(collapse(line)))
        continue
      }
      const text = collapse(renderInline(child))
      if (text) {
        if (!first) first = text
        else extras.push(text)
      }
    }

    const marker = ordered ? `${index}. ` : '- '
    out.push(`${indent}${marker}${first}`)
    extras.forEach((line) => out.push(`${indent}  ${line}`))
    nestedLines.forEach((line) => out.push(line))
    index += 1
  }

  return out.join('\n')
}

function cleanCell(el: Element): string {
  return collapse(renderInline(el))
}

function escapeCell(value: string): string {
  return value.replace(/\|/g, '\\|').replace(/\n/g, ' ')
}

function tableToChunk(el: Element): string | null {
  let table = el
  if (el.tagName.toUpperCase() !== 'TABLE') {
    table = el.querySelector('table') ?? el
  }
  const rows = Array.from(table.querySelectorAll('tr'))
  const grid: string[][] = []
  rows.forEach((tr) => {
    const cells = Array.from(tr.children)
      .filter((c): c is Element => isElementNode(c) && (c.tagName.toUpperCase() === 'TH' || c.tagName.toUpperCase() === 'TD'))
      .map((cell) => escapeCell(cleanCell(cell)))
    grid.push(cells)
  })
  if (!grid.length) return null

  const cols = Math.max(...grid.map((row) => row.length))
  const pad = (row: string[]) => {
    const copy = [...row]
    while (copy.length < cols) copy.push('')
    return copy
  }
  const body = grid.map(pad)
  const [header, ...rest] = body
  const lines = [`| ${header.join(' | ')} |`, `| ${Array(cols).fill('---').join(' | ')} |`]
  rest.forEach((row) => lines.push(`| ${row.join(' | ')} |`))
  return lines.join('\n')
}

/**
 * HTML → markdown。把每个块聚合成字符串、以空行分隔；连续多个空行归一。
 */
export function htmlToMarkdown(html: string): string {
  const trimmed = String(html || '').trim()
  if (!trimmed) return ''
  const doc = new DOMParser().parseFromString(trimmed, 'text/html')
  const chunks = collectBlockChunks(doc.body)
  return chunks.join('\n\n').replace(/\n{3,}/g, '\n\n').trim()
}
