export const FOLDER_COLORS = [
  { name: '红色', value: '#ef4444' },
  { name: '橙色', value: '#f97316' },
  { name: '琥珀色', value: '#f59e0b' },
  { name: '黄色', value: '#eab308' },
  { name: '青柠色', value: '#84cc16' },
  { name: '绿色', value: '#22c55e' },
  { name: '翠绿色', value: '#10b981' },
  { name: '青绿色', value: '#14b8a6' },
  { name: '青色', value: '#06b6d4' },
  { name: '天蓝色', value: '#0ea5e9' },
  { name: '蓝色', value: '#3b82f6' },
  { name: '靛蓝色', value: '#6366f1' },
  { name: '紫罗兰色', value: '#8b5cf6' },
  { name: '紫色', value: '#a855f7' },
  { name: '洋红色', value: '#d946ef' },
  { name: '粉色', value: '#ec4899' },
  { name: '玫红色', value: '#f43f5e' },
  { name: '棕色', value: '#92400e' },
  { name: '石板灰', value: '#64748b' },
  { name: '灰色', value: '#6b7280' },
  { name: '锌灰色', value: '#71717a' },
  { name: '中性灰', value: '#737373' },
  { name: '炭黑色', value: '#374151' },
  { name: '黑色', value: '#111827' },
] as const

export function isFolderColor(value: string): boolean {
  return FOLDER_COLORS.some((paletteColor) => paletteColor.value === value)
}

export function displayFolderColor(value?: string | null): string | null {
  return value && isFolderColor(value) ? value : null
}
