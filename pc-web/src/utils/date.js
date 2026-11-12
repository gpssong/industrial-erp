/**
 * 日期工具 —— 之前散在多个页面里各写一份 (如 xk/Material.vue 的 todayStr),
 * 口径不一致时导出文件名/生效日期会对不上。这里收敛成一处。
 */

/** yyyy-MM-dd (本地时区, 不用 toISOString —— 那会把东八区日期退一天)。 */
export function todayStr(d = new Date()) {
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** yyyy-MM-dd HH:mm:ss */
export function dateTimeStr(d = new Date()) {
  const p = n => String(n).padStart(2, '0')
  return `${todayStr(d)} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 日期时间戳截到分钟: 'YYYY-MM-DD HH:mm' */
export function minuteStr(v) {
  if (!v) return ''
  const d = v instanceof Date ? v : new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  const p = n => String(n).padStart(2, '0')
  return `${todayStr(d)} ${p(d.getHours())}:${p(d.getMinutes())}`
}
