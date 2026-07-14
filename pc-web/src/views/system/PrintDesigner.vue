<template>
  <div class="print-designer-wrapper">
    <!-- 顶部 toolbar -->
    <div class="designer-toolbar">
      <span class="toolbar-info">
        <el-icon><Document /></el-icon>
        纸张: {{ paperSizeLabel }} ({{ paperSizeMm }}mm) · Fabric.js 自研设计器
      </span>
      <div class="toolbar-actions">
        <el-button-group size="small">
          <el-button @click="designer?.undo()" :disabled="!canUndo" title="撤销 (Ctrl+Z)">
            <el-icon><Back /></el-icon>
          </el-button>
          <el-button @click="designer?.redo()" :disabled="!canRedo" title="重做 (Ctrl+Y)">
            <el-icon><RefreshRight /></el-icon>
          </el-button>
        </el-button-group>
        <el-divider direction="vertical" />
        <el-button-group size="small">
          <el-button @click="addObject('text')" title="文本">
            <el-icon><EditPen /></el-icon> 文本
          </el-button>
          <el-button @click="addObject('rect')" title="矩形">
            <el-icon><FullScreen /></el-icon> 矩形
          </el-button>
          <el-button @click="addObject('line')" title="线条">
            <el-icon><Minus /></el-icon> 线条
          </el-button>
          <el-button @click="addObject('image')" title="图片">
            <el-icon><Picture /></el-icon> 图片
          </el-button>
          <el-button @click="addObject('table')" title="表格">
            <el-icon><Grid /></el-icon> 表格
          </el-button>
        </el-button-group>
        <el-divider direction="vertical" />
        <el-button @click="clearAll" :disabled="!ready" type="warning" plain size="small">清空</el-button>
        <el-button @click="$emit('cancel')" size="small">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave" :disabled="!ready" size="small">
          保存设计
        </el-button>
      </div>
    </div>

    <div class="designer-body">
      <!-- 左侧: 字段库 (可拖入画布) -->
      <aside class="left-panel">
        <div class="panel-title">字段库</div>
        <el-collapse v-model="openGroups">
          <el-collapse-item v-for="grp in fieldGroups" :key="grp.name" :name="grp.name" :title="grp.label">
            <div
              v-for="f in grp.fields"
              :key="f.value"
              class="field-chip"
              draggable="true"
              @dragstart="onFieldDragStart($event, f)"
            >
              <span class="chip-label">{{ f.label }}</span>
              <code v-pre class="chip-name">{{'{{' + f.value + '}}'}}</code>
            </div>
          </el-collapse-item>
        </el-collapse>
        <div class="panel-hint">
          <div>💡 拖拽字段到画布自动创建文本元素</div>
          <div>💡 单击元素在右侧改属性</div>
        </div>
      </aside>

      <!-- 中间: Fabric 画布 -->
      <section class="canvas-stage">
        <div ref="canvasHost" class="canvas-host">
        <canvas ref="fabricCanvasEl"></canvas>
      </div>
      </section>

      <!-- 右侧: 属性面板 -->
      <aside class="right-panel">
        <div class="panel-title">属性</div>
        <div v-if="!selected" class="empty">未选中元素, 单击画布上的元素</div>
        <template v-else>
          <el-form label-width="80px" size="small">
            <el-form-item label="类型">
              <span class="type-badge">{{ selected.type }}</span>
            </el-form-item>
            <el-form-item label="位置 (mm)">
              <el-input-number :model-value="MM(selected.left)" :min="0" :max="500"
                @change="v => updateProp('left', mmToPx(v))" controls-position="right" style="width:80px" />
              <el-input-number :model-value="MM(selected.top)" :min="0" :max="500"
                @change="v => updateProp('top', mmToPx(v))" controls-position="right" style="width:80px;margin-left:6px" />
            </el-form-item>
            <el-form-item label="尺寸 (mm)">
              <el-input-number :model-value="MM(selected.width)" :min="1" :max="500"
                @change="v => updateProp('width', mmToPx(v))" controls-position="right" style="width:80px" />
              <el-input-number :model-value="MM(selected.height)" :min="1" :max="500"
                @change="v => updateProp('height', mmToPx(v))" controls-position="right" style="width:80px;margin-left:6px" />
            </el-form-item>
            <el-form-item v-if="selected.type === 'textbox'" label="文本">
              <el-input v-model="selected.text" @input="requestRender" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item v-if="selected.type === 'textbox'" label="绑定字段">
              <el-select v-model="selectedBind" filterable clearable placeholder="选择字段"
                @change="onBindChange" style="width:100%">
                <el-option v-for="f in flatFields" :key="f.value"
                  :label="`${f.label} (${f.value})`" :value="f.value" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="selected.type === 'textbox'" label="字号">
              <el-input-number v-model="selected.fontSize" :min="6" :max="72"
                @change="requestRender" controls-position="right" style="width:100%" />
            </el-form-item>
            <el-form-item v-if="selected.type === 'textbox'" label="颜色">
              <el-color-picker v-model="selected.fill" @change="requestRender" />
            </el-form-item>
            <el-form-item v-if="selected.type === 'rect'" label="填充">
              <el-color-picker v-model="selected.fill" @change="requestRender" />
            </el-form-item>
            <el-form-item v-if="selected.type === 'rect'" label="边框">
              <el-color-picker v-model="selected.stroke" @change="requestRender" />
            </el-form-item>
            <el-form-item v-if="selected.type === 'rect'" label="边框宽">
              <el-input-number v-model="selected.strokeWidth" :min="0" :max="10" :step="0.5"
                @change="requestRender" controls-position="right" style="width:100%" />
            </el-form-item>
            <el-form-item v-if="selected.type === 'group' && selected.isTable" label="表格列数">
              <el-input-number :model-value="(selected.data?.columns || []).length"
                :min="1" :max="10" disabled style="width:100%" />
            </el-form-item>
          </el-form>
          <el-button @click="deleteSelected" type="danger" plain size="small" style="width:100%;margin-top:12px">
            <el-icon><Delete /></el-icon> 删除元素
          </el-button>
        </template>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick, shallowRef, markRaw } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Document, Back, RefreshRight, EditPen, FullScreen, Minus, Picture, Grid, Delete
} from '@element-plus/icons-vue'
import { Canvas, Textbox, Rect, Line, FabricImage } from 'fabric'
import { legacyToFabric } from '@/print/designer/migrateLegacy'

const props = defineProps({
  templateJson: { type: String, default: '' },
  paperSize: { type: String, default: 'P80' },
  fields: { type: Array, default: () => [] },
})
const emit = defineEmits(['save', 'cancel'])

const paperSizeMm = computed(() => ({ P76: 76, P80: 80, P241: 241, A5: 148, A4: 210 }[props.paperSize] || 80))
const paperSizeLabel = computed(() => ({ P76: '76mm 小票', P80: '80mm 小票', P241: '241mm 三等分', A5: 'A5 纸', A4: 'A4 纸' }[props.paperSize] || props.paperSize))
const paperHeightMm = computed(() => paperSizeMm.value === 241 ? 93 : (paperSizeMm.value === 80 ? 200 : (paperSizeMm.value === 148 ? 210 : 297)))

// 96 DPI 换算
const dpi = 96
const mmToPx = mm => Math.round(mm * dpi / 25.4)
const pxToMm = px => Math.round((px / dpi * 25.4) * 10) / 10
const MM = v => pxToMm(v)

// ========== 字段定义 ==========
const allFields = {
  common: [
    { value: 'billNo', label: '单号' },
    { value: 'billDate', label: '单据日期' },
    { value: 'remark', label: '备注' },
  ],
  sal: [
    { value: 'customerName', label: '客户名称' },
    { value: 'customerCode', label: '客户编码' },
    { value: 'warehouseName', label: '仓库' },
    { value: 'salesmanName', label: '业务员' },
    { value: 'address', label: '收货地址' },
    { value: 'phone', label: '收货电话' },
  ],
  pur: [
    { value: 'supplierName', label: '供应商名称' },
    { value: 'supplierCode', label: '供应商编码' },
    { value: 'warehouseName', label: '仓库' },
    { value: 'buyerName', label: '采购员' },
  ],
  prd: [
    { value: 'bomNo', label: 'BOM 编号' },
    { value: 'productName', label: '产品名称' },
    { value: 'productCode', label: '产品编码' },
    { value: 'spec', label: '规格' },
    { value: 'workshop', label: '车间' },
  ],
  detail: [
    { value: 'productName', label: '商品名' },
    { value: 'productCode', label: '商品编码' },
    { value: 'spec', label: '规格' },
    { value: 'unitName', label: '单位' },
    { value: 'qty', label: '数量' },
    { value: 'price', label: '单价' },
    { value: 'amount', label: '金额' },
  ],
}
const fieldsByType = {
  SAL_DELIVERY: ['common', 'sal', 'detail'],
  PUR_RECEIPT: ['common', 'pur', 'detail'],
  PRD_ORDER: ['common', 'prd', 'detail'],
  PUR_RETURN: ['common', 'pur', 'detail'],
  SAL_RETURN: ['common', 'sal', 'detail'],
}
const allFieldList = computed(() => {
  // 单据类型不知道, 给全字段
  const seen = new Set()
  const out = []
  for (const arr of Object.values(allFields)) {
    for (const f of arr) {
      if (!seen.has(f.value)) { seen.add(f.value); out.push(f) }
    }
  }
  return out
})
const fieldGroups = computed(() => {
  return Object.keys(allFields).map(name => ({
    name,
    label: ({ common: '通用', detail: '明细', sal: '销售', pur: '采购', prd: '生产' })[name] || name,
    fields: allFields[name] || [],
  }))
})
const flatFields = computed(() => props.fields?.length ? props.fields : allFieldList.value)
const openGroups = ref(['common', 'detail'])

// ========== 状态 ==========
const canvasHost = ref(null)
const fabricCanvasEl = ref(null)
// 关键: Fabric Canvas 用 shallowRef + markRaw, 避免 Vue 代理
const designer = shallowRef(null)
const ready = ref(false)
const saving = ref(false)
const selected = ref(null)
const selectedBind = ref('')
const canUndo = ref(false)
const canRedo = ref(false)
const historyCursor = ref(-1)
const history = ref([])

function pushHistory() {
  if (!designer.value) return
  // Fabric v6 用 toJSON(), 没有 getJSON
  const json = designer.value.toJSON(['bind', 'data', 'isTable'])
  // 截断未来分支
  history.value = history.value.slice(0, historyCursor.value + 1)
  history.value.push(JSON.stringify(json))
  if (history.value.length > 50) history.value.shift()
  historyCursor.value = history.value.length - 1
  canUndo.value = historyCursor.value > 0
  canRedo.value = false
}

function doUndo() {
  if (!canUndo.value) return
  historyCursor.value--
  const json = history.value[historyCursor.value]
  if (json) designer.value?.loadJSON(json)
  canUndo.value = historyCursor.value > 0
  canRedo.value = true
}

function doRedo() {
  if (!canRedo.value) return
  historyCursor.value++
  const json = history.value[historyCursor.value]
  if (json) designer.value?.loadJSON(json)
  canRedo.value = historyCursor.value < history.value.length - 1
  canUndo.value = true
}

// ========== Canvas 初始化 ==========
async function initCanvas() {
  if (!canvasHost.value) return
  const wPx = mmToPx(paperSizeMm.value)
  const hPx = mmToPx(paperHeightMm.value)
  // Fabric v6 构造函数第一个参数:
  //   1) HTMLCanvasElement → 直接使用 (✅ 正确方式)
  //   2) id 字符串 → getElementById 查找
  //   3) 其他 (如 div) → 创建游离 canvas (不附加到 DOM → 不可见!)
  // 修复: 从 ref 获取预创建的 canvas 元素传进去
  const preCanvas = fabricCanvasEl.value
  if (!preCanvas) {
    console.error('[PrintDesigner] Pre-created canvas ref is null!')
    return
  }
  const c = markRaw(new Canvas(preCanvas, {
    width: wPx,
    height: hPx,
    backgroundColor: '#ffffff',
    preserveObjectStacking: true,
    selection: true,
    // 关键: 显式让 Fabric 用自身 CSS 控制 canvas
    enableRetinaScaling: false,
  }))
  // 强制 canvas DOM 元素可见
  setTimeout(() => {
    if (!canvasHost.value) return
    const cEls = canvasHost.value.querySelectorAll('canvas')
    cEls.forEach(el => {
      el.style.cssText = 'display:block;position:relative;top:0;left:0;z-index:0;'
    })
    console.log('[PrintDesigner] ✅ canvas mounted:', cEls.length, 'parent:', canvasHost.value.offsetWidth, 'x', canvasHost.value.offsetHeight, 'canvas size:', c.width, 'x', c.height)
  }, 100)
  c.on('selection:created', e => updateSelection(e.selected?.[0]))
  c.on('selection:updated', e => updateSelection(e.selected?.[0]))
  c.on('selection:cleared', () => { selected.value = null; selectedBind.value = '' })
  c.on('object:modified', pushHistory)
  c.on('object:added', pushHistory)
  c.on('object:removed', pushHistory)

  // 显式给 host 元素设 width/height, 让 Fabric 知道容器尺寸
  if (canvasHost.value) {
    canvasHost.value.style.width = wPx + 'px'
    canvasHost.value.style.height = hPx + 'px'
  }

  designer.value = c
  ready.value = true

  // 加载已有模板
  if (props.templateJson && props.templateJson.trim()) {
    try {
      const tpl = legacyToFabric(props.templateJson)
      c.loadFromJSON(tpl, () => {
        c.renderAll()
        // 初始化历史栈 (一个初始快照)
        history.value = []
        historyCursor.value = -1
        pushHistory()
      })
    } catch (e) {
      console.warn('[PrintDesigner] load template failed:', e)
      pushHistory()
    }
  } else {
    pushHistory()
  }
}

function updateSelection(obj) {
  if (!obj) { selected.value = null; selectedBind.value = ''; return }
  // markRaw 避免 Vue 代理
  selected.value = markRaw(obj)
  selectedBind.value = obj.bind || ''
}

// ========== 字段拖入 ==========
function onFieldDragStart(e, f) {
  e.dataTransfer.setData('application/json', JSON.stringify({ value: f.value, label: f.label }))
}

function onCanvasDrop(e) {
  e.preventDefault()
  const raw = e.dataTransfer.getData('application/json')
  if (!raw) return
  let f
  try { f = JSON.parse(raw) } catch (e) { return }
  const rect = canvasHost.value.getBoundingClientRect()
  const xPx = e.clientX - rect.left
  const yPx = e.clientY - rect.top
  addObject('text', { x: pxToMm(xPx), y: pxToMm(yPx), text: '{{' + f.value + '}}', bind: f.value, width: 40 })
}

function addObject(type, opts = {}) {
  if (!designer.value) return
  const c = designer.value
  let obj
  const x = mmToPx(opts.x ?? 10)
  const y = mmToPx(opts.y ?? 10)
  const w = mmToPx(opts.width ?? 40)
  const h = mmToPx(opts.height ?? 12)
  if (type === 'text') {
    obj = new Textbox(opts.text || '文本', {
      left: x, top: y, width: w, height: h,
      fontSize: opts.fontSize ?? 12,
      fontFamily: "'PingFang SC','Microsoft YaHei','微软雅黑',sans-serif",
      fill: '#000000',
    })
    if (opts.bind) obj.bind = opts.bind
  } else if (type === 'rect') {
    obj = new Rect({
      left: x, top: y, width: w, height: h,
      // 不要用白色填充 (与画布背景同色会看不见), 默认浅灰
      fill: '#f5f7fa', stroke: '#333333', strokeWidth: 1,
    })
  } else if (type === 'line') {
    obj = new Line([x, y, x + w, y + h], {
      stroke: '#333333', strokeWidth: 1,
    })
  } else if (type === 'image') {
    const url = window.prompt('图片 URL (http://...)')
    if (!url) return
    FabricImage.fromURL(url, { crossOrigin: 'anonymous' }).then(img => {
      img.set({ left: x, top: y, scaleX: w / img.width, scaleY: h / img.height })
      c.add(img)
      c.setActiveObject(img)
    }).catch(e => ElMessage.error('图片加载失败: ' + e.message))
    return
  } else if (type === 'table') {
    // Phase 2: 完整表格编辑器 (custom TableGroup 子类)
    // 当前: 用 Rect + Textbox 占位, 标 isTable + data
    const cols = (props.fields?.filter(f => f.group === 'detail') || allFields.detail).slice(0, 4)
    if (!cols.length) { ElMessage.warning('无可用字段'); return }
    const headers = cols.map(c => ({ header: c.label, bind: c.value, width: 60 }))
    // 用 Rect 当占位容器 (避免 Group 空 children 的 JSON 序列化 bug)
    obj = new Rect({
      left: x, top: y, width: w, height: h,
      fill: '#f5f7fa', stroke: '#409eff', strokeWidth: 1,
      strokeDashArray: [4, 4],
    })
    obj.isTable = true
    obj.data = { columns: headers, rowHeight: 24, placeholder: true }
  }
  if (obj) {
    // 关键: setCoords() 让 Fabric 计算对象的 left/top 边界, 否则 add() 后对象无尺寸不绘制
    obj.setCoords()
    c.add(obj)
    obj.setCoords()  // add 之后再 set 一次确保
    c.setActiveObject(obj)
    c.requestRenderAll()
    c.renderAll()  // 双保险: 同步 renderAll
    console.log('[PrintDesigner] addObject:', type, 'total:', c.getObjects().length,
      'obj.left:', obj.left, 'top:', obj.top, 'w:', obj.width, 'h:', obj.height,
      'canvas size:', c.width, 'x', c.height,
      'aabb:', obj.getBoundingRect())
  }
}

function clearAll() {
  if (!designer.value) return
  designer.value.getObjects().forEach(o => designer.value.remove(o))
  designer.value.renderAll()
}

function deleteSelected() {
  if (!designer.value || !selected.value) return
  designer.value.remove(selected.value)
  designer.value.renderAll()
}

function updateProp(key, val) {
  if (!selected.value) return
  selected.value.set(key, val)
  selected.value.setCoords()
  designer.value?.renderAll()
}

function requestRender() {
  designer.value?.requestRenderAll()
}

function onBindChange(newBind) {
  if (!selected.value) return
  selected.value.bind = newBind || ''
  if (newBind) {
    // 自动更新文本
    selected.value.text = '{{' + newBind + '}}'
  }
  requestRender()
}

// ========== 保存 ==========
function onSave() {
  if (!designer.value) return
  if (!designer.value.getObjects().length) {
    ElMessage.warning('请先添加元素')
    return
  }
  const json = designer.value.toJSON(['bind', 'data', 'isTable'])
  const out = {
    version: 1,
    paperWidth: paperSizeMm.value,
    paperHeight: paperHeightMm.value,
    unit: 'mm',
    objects: json.objects,
  }
  emit('save', JSON.stringify(out))
}

// ========== 键盘快捷键 ==========
function onKeydown(e) {
  if (!designer.value) return
  // 在输入框/textarea 中不触发
  const tag = (e.target?.tagName || '').toLowerCase()
  if (tag === 'input' || tag === 'textarea') return
  if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
    e.preventDefault()
    doUndo()
  } else if ((e.ctrlKey || e.metaKey) && (e.key === 'y' || (e.shiftKey && e.key === 'Z'))) {
    e.preventDefault()
    doRedo()
  } else if (e.key === 'Delete' || e.key === 'Backspace') {
    if (selected.value) {
      e.preventDefault()
      deleteSelected()
    }
  }
}

// ========== 生命周期 ==========
onMounted(() => {
  // 关键: Fabric v6 内部有 3 个 setup 步骤 (创建 lower-canvas + upper-canvas + wrapper div),
  // 需要 DOM 完全就绪 + 父级布局完成. 用 setTimeout 0 推一帧
  setTimeout(() => initCanvas(), 0)
  // 拖放监听也加 setTimeout 避免 host 元素未就绪
  setTimeout(() => {
    if (canvasHost.value) {
      canvasHost.value.addEventListener('dragover', e => e.preventDefault())
      canvasHost.value.addEventListener('drop', onCanvasDrop)
    }
  }, 50)
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (designer.value) {
    try { designer.value.dispose() } catch (e) {}
    designer.value = null
  }
  ready.value = false
  // 清理 ref
  fabricCanvasEl.value = null
})

// 模板变化时重新加载
watch(() => props.templateJson, async (val) => {
  if (!ready.value) return
  if (!designer.value) return
  if (!val) return
  const tpl = legacyToFabric(val)
  designer.value.loadFromJSON(tpl, () => {
    designer.value.renderAll()
    history.value = []
    historyCursor.value = -1
    pushHistory()
  })
})

// 纸张大小变化时重建 canvas
watch(() => props.paperSize, () => {
  if (designer.value) {
    try { designer.value.dispose() } catch (e) {}
    designer.value = null
  }
  ready.value = false
  setTimeout(() => initCanvas(), 0)
})

// 监听 templateJson 为空时也重置
watch(() => props.templateJson, (val) => {
  if (!val && designer.value) {
    designer.value.clear()
    designer.value.renderAll()
  }
})
</script>

<style scoped>
.print-designer-wrapper {
  display: flex;
  flex-direction: column;
  height: 78vh;
  min-height: 600px;
}
.designer-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  margin-bottom: 8px;
}
.toolbar-info {
  flex: 1;
  color: #606266;
  font-size: 13px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.designer-body {
  flex: 1;
  display: flex;
  gap: 8px;
  overflow: hidden;
}
.left-panel,
.right-panel {
  width: 240px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px;
  overflow-y: auto;
}
.canvas-stage {
  flex: 1;
  background: #f0f0f0;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: auto;
}
.canvas-host {
  display: block;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  position: relative;
  min-width: 400px;
  min-height: 200px;
}
.canvas-host > .canvas-container,
.canvas-host > canvas,
.canvas-host canvas.upper-canvas,
.canvas-host canvas.lower-canvas {
  display: block;
  position: relative !important;
  top: 0 !important;
  left: 0 !important;
}
.panel-title {
  font-weight: 600;
  margin-bottom: 12px;
  color: #303133;
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 8px;
}
.field-chip {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  margin-bottom: 4px;
  background: #f5f7fa;
  border-radius: 4px;
  cursor: grab;
  font-size: 12px;
  transition: background 0.2s;
}
.field-chip:hover {
  background: #ecf5ff;
  color: #409eff;
}
.chip-label {
  font-weight: 500;
}
.chip-name {
  font-size: 11px;
  color: #909399;
  font-family: monospace;
}
.panel-hint {
  margin-top: 16px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
.empty {
  text-align: center;
  color: #c0c4cc;
  padding: 40px 0;
  font-size: 13px;
}
.type-badge {
  display: inline-block;
  padding: 2px 8px;
  background: #f0f9ff;
  color: #1890ff;
  border-radius: 3px;
  font-size: 12px;
  font-family: monospace;
}
</style>
