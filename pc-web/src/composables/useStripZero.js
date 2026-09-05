/**
 * v1.1.7 数字去尾 0 复用 hook
 *
 * 背景: Element Plus <el-input-number :precision="N"> 在 v-model 装载数值时
 *       会强制按 precision=N 显示 (例如 :precision="4" → 735.0000),
 *       即使 :step-strictly="false" 也仅对用户输入生效, 对装载值不生效.
 *
 * 解决: 用 EP 提供的 :formatter + :parser hook 接管显示:
 *   - stripZeroFormat(value): 显示时把 Number 去尾 0
 *   - stripZeroParse(display): 解析用户输入回 Number
 *
 * 用法:
 *   <el-input-number v-model="row.qty" :min="0"
 *     :formatter="stripZeroFormat" :parser="stripZeroParse" />
 *
 * 两个工具函数用于纯展示列 (不含 EP 输入):
 *   - stripTrailingZero4(v): 用于"数量"列 (最多 4 位小数, 去尾)
 *   - stripTrailingZero2(v): 用于"金额"列 (最多 2 位小数, 去尾, 整数不显示小数点)
 */
export function useStripZero() {
  const stripZeroFormat = (v) => {
    if (v == null || v === '') return ''
    const n = Number(v)
    if (!isFinite(n)) return String(v)
    return String(n)
  }
  // v1.1.32: parser 必须返回原始字符串(而非 null)当用户正在输入中间态时(如 "1." / ".5")
  // 若返回 null, EP 会清空整个输入框. 只在完全无法解析时才返回 null
  const stripZeroParse = (v) => {
    if (v == null || v === '') return null
    const cleaned = String(v).replace(/,/g, '')
    // 允许中间态: 以 . 开头/结尾 或 纯点 的字符串原样返回, 不强制转 Number
    if (/^-?\d*\.$|^-$/.test(cleaned)) return cleaned
    const n = Number(cleaned)
    return isFinite(n) ? n : cleaned // 无法解析时也返回原始字符串, 不返回 null
  }
  const stripTrailingZero4 = (v) => {
    if (v == null || v === '' || !isFinite(Number(v))) return ''
    const n = Number(v)
    return Number.isInteger(n) ? String(n) : n.toString()
  }
  const stripTrailingZero2 = (v) => {
    if (v == null || v === '' || !isFinite(Number(v))) return ''
    const n = Number(v)
    return Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/\.?0+$/, '')
  }
  return { stripZeroFormat, stripZeroParse, stripTrailingZero4, stripTrailingZero2 }
}
