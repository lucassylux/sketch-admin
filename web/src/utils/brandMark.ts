/**
 * 品牌运行时能力（sketch-admin 模板通用）：
 *  - loadBrand()：拉 GET /api/settings/brand（starter 契约，登录前公开读），失败静默走默认
 *  - lettermarkSvg()：Logo 未配置时按应用名首字生成徽标（通用兜底，替代硬编码默认图）
 *  - sanitizeBrandSvg()：SVG 白名单消毒（v-html 渲染服务端 SVG 是存储型 XSS 面，必须过滤）
 */
import { ref } from 'vue'

export interface BrandConfig {
  logoSvg: string
  iconSvg: string
  appName: string
  tagline: string
  copyrightText: string
}

const brand = ref<BrandConfig>({ logoSvg: '', iconSvg: '', appName: '', tagline: '', copyrightText: '' })
let loaded = false

const SVG_TAGS = new Set(['svg', 'defs', 'linearGradient', 'radialGradient', 'stop', 'g', 'rect', 'circle',
  'ellipse', 'path', 'line', 'polygon', 'polyline', 'text', 'tspan', 'title'])
const SVG_ATTRS = new Set(['viewBox', 'width', 'height', 'x', 'y', 'x1', 'y1', 'x2', 'y2', 'cx', 'cy', 'r',
  'rx', 'ry', 'd', 'points', 'fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'opacity',
  'transform', 'offset', 'stop-color', 'stop-opacity', 'gradientUnits', 'text-anchor', 'font-family',
  'font-size', 'font-weight', 'textLength', 'lengthAdjust', 'id'])

export function sanitizeBrandSvg(raw: string | undefined | null): string {
  if (!raw?.trim()) return ''
  try {
    const doc = new DOMParser().parseFromString(raw, 'image/svg+xml')
    if (doc.querySelector('parsererror')) return ''
    const walk = (el: Element) => {
      for (const child of Array.from(el.children)) {
        if (!SVG_TAGS.has(child.tagName)) { child.remove(); continue }
        for (const attr of Array.from(child.attributes)) {
          if (!SVG_ATTRS.has(attr.name) || attr.value.includes('javascript:')) child.removeAttribute(attr.name)
        }
        walk(child)
      }
    }
    const root = doc.documentElement
    if (root.tagName.toLowerCase() !== 'svg') return ''
    for (const attr of Array.from(root.attributes)) {
      if (!SVG_ATTRS.has(attr.name)) root.removeAttribute(attr.name)
    }
    walk(root)
    return new XMLSerializer().serializeToString(root)
  } catch {
    return ''
  }
}

/** 首字标（lettermark）：应用名首字生成 SVG 徽标（favicon 同样适用） */
export function lettermarkSvg(appName: string | undefined, fallbackChar: string): string {
  const ch = (appName?.trim() || fallbackChar).charAt(0).toUpperCase()
  return ('<svg viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg">'
    + '<defs><linearGradient id="lmk" x1="0" y1="0" x2="1" y2="1">'
    + '<stop offset="0" stop-color="#6366f1"/><stop offset="1" stop-color="#a855f7"/></linearGradient></defs>'
    + '<rect width="512" height="512" rx="112" fill="url(#lmk)"/>'
    + `<text x="256" y="356" text-anchor="middle" font-family="Arial, sans-serif" font-size="280" font-weight="700" fill="#ffffff">${ch}</text>`
    + '</svg>')
}

/** 应用图标到浏览器页签（SVG data URI favicon） */
const applyFavicon = (iconSvg: string) => {
  if (!iconSvg) return
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (!link) {
    link = document.createElement('link')
    link.rel = 'icon'
    document.head.appendChild(link)
  }
  link.type = 'image/svg+xml'
  link.href = 'data:image/svg+xml,' + encodeURIComponent(iconSvg)
}

export async function loadBrand(): Promise<void> {
  if (loaded) return
  loaded = true
  try {
    const b = await fetch('/api/settings/brand').then((r) => r.json())
    brand.value = {
      logoSvg: sanitizeBrandSvg(b?.logoSvg),
      iconSvg: sanitizeBrandSvg(b?.iconSvg),
      appName: typeof b?.appName === 'string' ? b.appName.slice(0, 64) : '',
      tagline: typeof b?.tagline === 'string' ? b.tagline.slice(0, 128) : '',
      copyrightText: typeof b?.copyrightText === 'string' ? b.copyrightText.slice(0, 200) : '',
    }
    applyFavicon(brand.value.iconSvg || brand.value.logoSvg
      || lettermarkSvg(brand.value.appName, '?'))
    if (brand.value.appName) document.title = brand.value.appName
  } catch {
    /* 品牌端点不可用时各使用方走默认 */
  }
}

export function useBrand() {
  return brand
}
