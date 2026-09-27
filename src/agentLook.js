// Agent 虚拟形象的外观：配色预设 + 自定义颜色推算

// colors：四团光斑的颜色；base：球体底色
export const LOOK_PRESETS = [
  { key: 'aurora', label: '极光', base: '#7c8cf8', colors: ['#12c2a8', '#2f7cf6', '#f5a3c7', '#a996ff'] },
  { key: 'sunset', label: '晚霞', base: '#ff8a7a', colors: ['#ff9a62', '#ff5f7e', '#ffd36e', '#c86dd7'] },
  { key: 'forest', label: '森林', base: '#3fb58f', colors: ['#34c77b', '#0f9d8a', '#c6e86b', '#5fb3a1'] },
  { key: 'ocean', label: '深海', base: '#2b5fd9', colors: ['#1d4ed8', '#0ea5e9', '#6366f1', '#22d3ee'] },
  { key: 'lavender', label: '薰衣草', base: '#a78bfa', colors: ['#b69cff', '#8b7cf6', '#f0abfc', '#93c5fd'] },
  { key: 'graphite', label: '石墨', base: '#52606f', colors: ['#64748b', '#334155', '#94a3b8', '#475569'] },
]

export const LOOK_DEFAULT = { preset: 'aurora', custom: null, glasses: false }

// 由一个主色推算出协调的一组光斑色：色相左右偏移，亮度错开
export function paletteFrom(hex) {
  const [h, s, l] = hexToHsl(hex)
  const c = (dh, ds, dl) =>
    `hsl(${(h + dh + 360) % 360} ${clamp(s + ds, 20, 95)}% ${clamp(l + dl, 25, 82)}%)`
  return { base: c(0, 0, 0), colors: [c(0, 5, 4), c(32, 0, -6), c(-38, -10, 18), c(14, -5, 12)] }
}

export function resolveLook(look) {
  if (look.custom) return paletteFrom(look.custom)
  return LOOK_PRESETS.find((p) => p.key === look.preset) || LOOK_PRESETS[0]
}

function clamp(v, a, b) {
  return Math.min(b, Math.max(a, v))
}
function hexToHsl(hex) {
  const n = parseInt(hex.slice(1), 16)
  const r = ((n >> 16) & 255) / 255
  const g = ((n >> 8) & 255) / 255
  const b = (n & 255) / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const l = (max + min) / 2
  let h = 0
  let s = 0
  if (max !== min) {
    const d = max - min
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
    h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4
    h *= 60
  }
  return [Math.round(h), Math.round(s * 100), Math.round(l * 100)]
}
