<!--
  标签元素属性面板（docs/36 Task 8, P1）。
  口径（控制器 pre-flight §4，逐条实现）：
  - 只 emit `patch`（仅变动键，键名与后端 JSON 一致）；不直接改 props、不发任何业务请求、
    不持有第二份可保存 rows。容器接线是 Task 10 的活。
  - 所有控件 :model-value + @update:model-value，无 v-model="row.xxx"。
  - 打开面板/换选中元素不自动 emit、不自动写 mulX/mulY/cell 三键。
  - 倍率/cell 显示 resolved 给的有效倍率（LabelGeometry.effectiveMul 的结果）；写回只在用户改动时。
  - 字高展示 = resolved.segments[0].h × 25.4 / trueDpi(dpi)，segments 空 ⇒「等待几何解析」；
    不在 JS 里重算字宽/拆行/由 size 推倍率（全局约束 5）。
  - qrcode 只给 cell（2…8 整数），绝不碰 size；barcode 只给 h，不出宽/字倍控件；
    line 给 w/h（h 即线厚 R13）；rect 给 w/h。无 rotate 相关任何控件（全局约束 2）。
  - 必填缺值（resolved.missing）红提示，但本面板不含保存动作、不做禁止保存。
  - 对齐说明：保留模板属性，当前 TSPL 以 x 坐标定位（R12）。
  - 后端 issue 点名的未知字段字符串不得被控件隐藏。
-->
<template>
  <div class="label-inspector">
    <div v-if="!row" class="li-empty">选择一个元素以编辑属性</div>

    <template v-else>
      <div class="li-head">
        <span class="li-type">{{ type }}</span>
        <span v-if="resolved && resolved.index != null" class="li-index">#{{ resolved.index }}</span>
      </div>

      <div v-if="resolved && resolved.missing" class="li-issue li-missing" data-test="missing-hint">
        必填字段缺值：{{ resolved.field || row.field || '(未填字段)' }} —— 仅提示，不阻止保存。
      </div>
      <div v-for="(msg, i) in (resolved && resolved.issues ? resolved.issues : [])" :key="i" class="li-issue">
        {{ msg }}
      </div>

      <!-- text / multiline -->
      <template v-if="isTextType">
        <div class="li-row">
          <label class="li-label">字段</label>
          <el-select :model-value="row.field || ''" clearable filterable size="small"
                     class="li-ctl" placeholder="取上下文字段"
                     @update:model-value="onField">
            <el-option v-for="f in fields" :key="f.name" :label="`${f.label} ${f.name}`" :value="f.name" />
            <!-- 未知字段字符串照原样显示，不因换控件被藏 -->
            <el-option v-if="unknownField" :label="`${row.field}（未知字段）`" :value="row.field" />
          </el-select>
        </div>
        <div class="li-row">
          <label class="li-label">字面文本</label>
          <el-input :model-value="row.text || ''" size="small" class="li-ctl"
                    @update:model-value="onText" />
        </div>
        <div class="li-row">
          <label class="li-label">x（点）</label>
          <el-input-number :model-value="num(row.x)" :min="0" size="small" class="li-ctl"
                           @update:model-value="setInteger('x', $event)" />
        </div>
        <div class="li-row">
          <label class="li-label">y（点）</label>
          <el-input-number :model-value="num(row.y)" :min="0" size="small" class="li-ctl"
                           @update:model-value="setInteger('y', $event)" />
        </div>
        <div class="li-row">
          <label class="li-label">横向倍率 mulX</label>
          <el-input-number :model-value="eff('mulX')" :min="1" size="small" class="li-ctl"
                           @update:model-value="setInteger('mulX', $event)" />
        </div>
        <div class="li-row">
          <label class="li-label">纵向倍率 mulY</label>
          <el-input-number :model-value="eff('mulY')" :min="1" size="small" class="li-ctl"
                           @update:model-value="setInteger('mulY', $event)" />
        </div>
        <div class="li-row">
          <label class="li-label">字高（解析）</label>
          <span class="li-readonly" data-test="glyph-mm">{{ glyphMmText }}</span>
        </div>
        <div v-if="type === 'multiline'" class="li-row">
          <label class="li-label">多行声明宽 w（点）</label>
          <el-input-number :model-value="num(row.w)" :min="0" size="small" class="li-ctl"
                           @update:model-value="setInteger('w', $event)" />
        </div>
        <div class="li-row">
          <label class="li-label">对齐</label>
          <el-select :model-value="row.align || 'LEFT'" size="small" class="li-ctl"
                     @update:model-value="onAlign">
            <el-option value="LEFT" /><el-option value="CENTER" /><el-option value="RIGHT" />
          </el-select>
        </div>
        <div class="li-note">保留模板属性，当前TSPL以x坐标定位。</div>
        <div class="li-row">
          <label class="li-label">加粗</label>
          <el-checkbox :model-value="!!row.bold" @update:model-value="onBool('bold', $event)" />
        </div>
        <div class="li-row">
          <label class="li-label">必填</label>
          <el-checkbox :model-value="!!row.required" @update:model-value="onBool('required', $event)" />
        </div>
      </template>

      <!-- qrcode：只给 cell（2…8 整数档），不碰 size -->
      <template v-else-if="type === 'qrcode'">
        <div class="li-row">
          <label class="li-label">模块边长 cell（2~8）</label>
          <el-input-number :model-value="cellValue" :min="2" :max="8" :step="1" size="small"
                           class="li-ctl" @update:model-value="onCell" />
        </div>
        <div v-if="resolved && resolved.displayBounds && resolved.displayBounds.hint" class="li-note">
          {{ resolved.displayBounds.hint }}
        </div>
      </template>

      <!-- barcode：只给 h，不出宽/字倍控件 -->
      <template v-else-if="type === 'barcode'">
        <div class="li-row">
          <label class="li-label">高度 h（点）</label>
          <el-input-number :model-value="num(row.h)" :min="0" size="small" class="li-ctl"
                           @update:model-value="setInteger('h', $event)" />
        </div>
      </template>

      <!-- line：w + h（h 即线厚，R13） -->
      <template v-else-if="type === 'line'">
        <div class="li-row">
          <label class="li-label">线长 w（点）</label>
          <el-input-number :model-value="num(row.w)" :min="0" size="small" class="li-ctl"
                           @update:model-value="setInteger('w', $event)" />
        </div>
        <div class="li-row">
          <label class="li-label">线厚 h（点）</label>
          <el-input-number :model-value="num(row.h)" :min="0" size="small" class="li-ctl"
                           @update:model-value="setInteger('h', $event)" />
        </div>
      </template>

      <!-- rect：w + h -->
      <template v-else-if="type === 'rect'">
        <div class="li-row">
          <label class="li-label">宽 w（点）</label>
          <el-input-number :model-value="num(row.w)" :min="0" size="small" class="li-ctl"
                           @update:model-value="setInteger('w', $event)" />
        </div>
        <div class="li-row">
          <label class="li-label">高 h（点）</label>
          <el-input-number :model-value="num(row.h)" :min="0" size="small" class="li-ctl"
                           @update:model-value="setInteger('h', $event)" />
        </div>
      </template>

      <div v-else class="li-empty">该元素类型（{{ type }}）暂无可调属性</div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  row: Object, resolved: Object, fields: { type: Array, default: () => [] },
  dpi: { type: Number, default: 203 }
})
const emit = defineEmits(['patch'])

const type = computed(() => (props.row && props.row.type) || (props.resolved && props.resolved.type) || '')
const isTextType = computed(() => type.value === 'text' || type.value === 'multiline')
const unknownField = computed(() => {
  const f = props.row && props.row.field
  return !!f && !(props.fields || []).some(x => x.name === f)
})

/** 换算与服务端同源：203dpi 的真值是 203.2（LabelTemplate.vue:148-152，仅复制这一条算式） */
const trueDpi = (dpi) => (dpi === 203 ? 203.2 : dpi)
const glyphMmText = computed(() => {
  const segs = props.resolved && props.resolved.segments
  const h = segs && segs.length ? segs[0].h : null
  if (h == null) return '等待几何解析'
  return `${((h * 25.4) / trueDpi(props.dpi)).toFixed(2)} mm（segments[0].h=${h} 点）`
})

const cellValue = computed(() => {
  const declared = props.row ? props.row.cell : null
  const eff = declared != null && declared !== 0 ? declared : (props.resolved && props.resolved.cell)
  return eff == null || eff === 0 ? null : Math.round(Number(eff))
})

/** 有效倍率显示：未声明时读 resolved 给的值（pre-flight §1），不本地推导 */
function eff(key) {
  const declared = props.row ? props.row[key] : null
  if (declared != null && Number(declared) > 0) return Number(declared)
  const r = props.resolved ? props.resolved[key] : null
  return r == null ? 1 : Number(r)
}
function num(v) {
  return v == null || v === '' ? 0 : Number(v)
}

function setInteger(key, value) {
  if (value == null) return
  emit('patch', { [key]: Math.round(Number(value)) })
}
function onField(v) {
  // 清空字段 ⇒ field 以空字符串发出（不省略、不 null 化），与新 text 各自独立成 patch
  emit('patch', { field: v == null ? '' : String(v) })
}
function onText(v) {
  emit('patch', { text: v == null ? '' : String(v) })
}
function onAlign(v) {
  emit('patch', { align: v })
}
function onBool(key, v) {
  emit('patch', { [key]: !!v })
}
function onCell(v) {
  if (v == null) return
  const c = Math.min(8, Math.max(2, Math.round(Number(v))))
  emit('patch', { cell: c })
}
</script>

<style scoped>
.label-inspector { padding: 10px 12px; font-size: 13px; }
.li-empty { color: #909399; padding: 24px 8px; text-align: center; }
.li-head { display: flex; gap: 8px; align-items: baseline; margin-bottom: 8px; font-weight: 600; }
.li-index { color: #909399; font-weight: 400; }
.li-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.li-label { width: 132px; flex: none; color: #606266; }
.li-ctl { max-width: 220px; }
.li-readonly { color: #303133; }
.li-note { color: #909399; font-size: 12px; margin: 2px 0 8px 140px; }
.li-issue { color: #f56c6c; font-size: 12px; margin-bottom: 6px; }
.li-missing { font-weight: 600; }
</style>
