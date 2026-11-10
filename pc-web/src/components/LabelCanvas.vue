<!--
  标签图形化编辑画布（docs/36 Task 9, P1）—— 哑组件。

  口径（简报 Step 2 + 控制器 pre-flight §2/§3/§4 逐条）：
  - 几何只有服务端来源：盒子 x/y/w/h 一律取 resolved 元素的 displayBounds 与 segments，
    组件不从屏幕文本长度反推盒宽、不在 JS 里重算打印机字宽或拆行（全局约束 5）。
    一个源元素 = 一个选择组（.lc-group），组内 segments 逐盒渲染（.lc-seg-visual），绝不合并成大盒。
  - 换算只有简报这一条算式：pxPerDot = widthMm * zoom / widthDots；heightDots 配 heightMm
    校验同比例（比例不一致 ⇒ 可见横幅 + DEV 警告），不另起第二套算式。
  - 内容区尺寸 = widthDots*pxPerDot（边框在内容之外，box-sizing: content-box）。
  - estimated 的盒子（displayBounds.estimated）如实显示可见提示文案，不伪装点阵精度；
    服务端给了 hint 就用 hint。
  - 文字宽度校准只量 DOM 实际渲染宽：每个盒内一个 nowrap span，font-size = 盒高换算值；
    先清旧 transform 再测 getBoundingClientRect().width，偏差 >2% 才 scaleX(target/measured)，
    transform-origin: left top；零宽/空串不除 0。内容或 zoom 变化后 nextTick 重测，字体 ready 后重测，
    观察器在 unmount 清理。粗体只用轻微重复绘制（阴影 ≈ 打印 x+1），不改盒子宽度。
  - 不发任何业务请求，不 import api，不改 props；move/resize 只在手势结束提交一次，
    过程中只有本组件的临时位移。Ctrl/Cmd+Z、Shift+Z、S 由容器捕获，本组件不实现。
-->
<template>
  <div
    ref="rootRef"
    class="lc-root"
    tabindex="0"
    data-test="label-canvas"
    :data-calib="calibCount"
    :data-px-per-dot="pxPerDot"
    :data-ratio-ok="ratioOk ? '1' : '0'"
    :data-gesture="gesture ? gesture.kind + ':' + gesture.index : 'none'"
    @keydown="onRootKeydown"
    @focusout="onRootFocusOut"
  >
    <div v-if="!ratioOk" class="lc-ratio" data-test="ratio-warning">
      纸面毫米与点数比例不一致（x={{ pxPerDot }} / y={{ pxPerDotY }}），两轴各按自身比例显示，请核对纸头尺寸。
    </div>

    <div class="lc-scroll">
      <div class="lc-paper" :style="paperStyle" data-test="paper">
        <div class="lc-content" :style="contentStyle" data-test="paper-content">
          <div
            v-for="(el, i) in list"
            :key="idxOf(el, i)"
            class="lc-group"
            :data-el="idxOf(el, i)"
            :data-order="i"
            :data-type="el.type || ''"
            :data-seg-count="segCount(el)"
            :data-est="estOf(el) ? '1' : '0'"
            :class="{ 'is-selected': idxOf(el, i) === selectedIndex, 'is-dragging': isDragging(el, i) }"
            :style="groupStyle(el, i)"
            @pointerdown="onGroupDown(el, i, $event)"
            @pointermove="onGestureMove(el, i, $event)"
            @pointerup="onGestureUp(el, i, $event)"
            @pointercancel="onGestureDiscard(el, i, $event)"
            @lostpointercapture="onGestureDiscard(el, i, $event)"
          >
            <!-- 一个 segment 一个盒子（拆行/逐段全部画出来） -->
            <div
              v-for="(seg, k) in segsOf(el)"
              :key="k"
              class="lc-seg"
              :data-seg="k"
              :data-x="seg.x"
              :data-y="seg.y"
              :data-w="seg.w"
              :data-h="seg.h"
              :style="hitStyle(el, seg)"
            >
              <div class="lc-seg-visual" :style="visualStyle(el, seg)">
                <span
                  class="lc-seg-text"
                  data-cal="seg"
                  :data-target-w="pxX(seg.w)"
                  :class="{ 'is-bold': !!el.bold }"
                  :style="textStyle(seg)"
                >{{ seg.content == null ? '' : seg.content }}</span>
              </div>
            </div>

            <!-- segments 为空（估算盒 / 码图 / 缺值）⇒ 用服务端 displayBounds 画一个盒 -->
            <div v-if="segsOf(el).length === 0" class="lc-proxy" :style="hitStyle(el, dbOf(el))">
              <div class="lc-seg-visual" :style="visualStyle(el, dbOf(el))">
                <img v-if="el.svg" class="lc-img" :src="el.svg" alt="" draggable="false" @dragstart.prevent />
              </div>
            </div>

            <div v-if="hintOf(el)" class="lc-hint" :style="hintStyle(el, 0)" data-test="est-hint">{{ hintOf(el) }}</div>
            <!-- 缺值标记独立成行：估算提示与「必填缺值」可以同时出现（元素 4 两者皆有） -->
            <div v-if="el.missing" class="lc-hint lc-hint--missing" :style="hintStyle(el, hintOf(el) ? 1 : 0)" data-test="missing-hint">
              {{ (el.displayLabel || el.field || '') + '（必填缺值）' }}
            </div>

            <!-- 缩放手柄：只看 type，档位规则属 Task 10；组件只回传 {index,deltaX,deltaY} -->
            <div
              v-for="h in handlesFor(el, i)"
              :key="h"
              class="lc-handle"
              :class="'lc-handle--' + h"
              :data-test="'resize-handle-' + h"
              :data-handle="h"
              :data-handle-axis="handleAxis(h)"
              @pointerdown.stop="onHandleDown(el, i, h, $event)"
              @pointermove.stop.prevent="onGestureMove(el, i, $event)"
              @pointerup.stop="onGestureUp(el, i, $event)"
              @pointercancel.stop="onGestureDiscard(el, i, $event)"
              @lostpointercapture.stop="onGestureDiscard(el, i, $event)"
            ></div>
          </div>

          <!-- 吸附辅助线：与本地临时位移同步，手势结束即消失 -->
          <div
            v-if="guideX"
            class="lc-guide lc-guide--x"
            data-test="snap-guide-x"
            :style="{ left: guideX.px + 'px' }"
            :data-guide-src="guideX.src"
            :data-guide-dots="guideX.dots"
            :data-guide-edge="guideX.which"
          ></div>
          <div
            v-if="guideY"
            class="lc-guide lc-guide--y"
            data-test="snap-guide-y"
            :style="{ top: guideY.px + 'px' }"
            :data-guide-src="guideY.src"
            :data-guide-dots="guideY.dots"
            :data-guide-edge="guideY.which"
          ></div>

          <!-- 缩放手势中只画虚线选择外框，不动任何真实盒子 -->
          <div v-if="resizeOutline" class="lc-resize-outline" data-test="resize-outline" :style="resizeOutline"></div>
        </div>
      </div>
    </div>

    <div v-if="list.length === 0" class="lc-blank" data-test="empty-canvas">空白纸面（没有元素）</div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  elements: { type: Array, default: () => [] },
  widthDots: { type: Number, default: 0 },
  heightDots: { type: Number, default: 0 },
  widthMm: { type: Number, default: 0 },
  heightMm: { type: Number, default: 0 },
  zoom: { type: Number, default: 6 },
  selectedIndex: { type: Number, default: -1 }
})
const emit = defineEmits(['select', 'move', 'resize', 'delete'])

/** 点击区下限：8 CSS px（细线/空文本的视觉尺寸不因此膨胀） */
const HIT_MIN_PX = 8
/** 估算盒的通用可见提示（服务端没给 hint 时用它，仍不许伪装点阵精度） */
const EST_HINT = '尺寸为估算示意，最终以实纸为准'
/** 吸附半径：8 点（简报 Step 3 / pre-flight §4） */
const SNAP_DOTS = 8
/** Shift 步进：8 点（全局约束 7） */
const STEP_DOTS = 8
/** 判定「真的拖动过」的最小像素行程：纯点击不提交 */
const MOVE_EPS_PX = 0.4

const list = computed(() => (Array.isArray(props.elements) ? props.elements : []))
const idxOf = (el, i) => (el && typeof el.index === 'number' ? el.index : i)
const dbOf = el => (el && el.displayBounds) || null
const segsOf = el => (el && Array.isArray(el.segments) ? el.segments : [])
const segCount = el => segsOf(el).length
const estOf = el => {
  const db = dbOf(el)
  return !!(db && db.estimated)
}

/* ---------- 换算（简报 Step 2 原文，只此一条算式） ---------- */
const pxPerDotRaw = computed(() => props.widthMm * props.zoom / props.widthDots)
const pxPerDot = computed(() => (Number.isFinite(pxPerDotRaw.value) && pxPerDotRaw.value > 0 ? pxPerDotRaw.value : 0))
const toDotDelta = pixels => {
  const d = Math.round(pixels / pxPerDot.value)
  return Number.isFinite(d) ? d : 0
}
// heightDots 配 heightMm 校验相同比例；比例一致时 y 轴共用上面那条算式，不另起第二套。
const pxPerDotYRaw = computed(() => props.heightMm * props.zoom / props.heightDots)
const pxPerDotY = computed(() => (Number.isFinite(pxPerDotYRaw.value) && pxPerDotYRaw.value > 0 ? pxPerDotYRaw.value : 0))
const ratioOk = computed(() => pxPerDot.value > 0 && Math.abs(pxPerDotY.value - pxPerDot.value) <= 1e-9)

const pxX = dots => (Number(dots) || 0) * pxPerDot.value
const pxY = dots => (Number(dots) || 0) * (ratioOk.value ? pxPerDot.value : pxPerDotY.value)

const contentW = computed(() => pxX(props.widthDots))
const contentH = computed(() => pxY(props.heightDots))
const paperStyle = computed(() => ({ width: contentW.value + 'px', height: contentH.value + 'px' }))
const contentStyle = computed(() => ({ width: contentW.value + 'px', height: contentH.value + 'px' }))

/* ---------- 盒子定位：全部来自服务端点值 ---------- */
function groupStyle(el, i) {
  const db = dbOf(el)
  if (!db) return { display: 'none' }
  const style = {
    left: pxX(db.x) + 'px',
    top: pxY(db.y) + 'px',
    width: pxX(db.w) + 'px',
    height: pxY(db.h) + 'px',
    zIndex: String(i + 1)
  }
  const g = gesture.value
  // 手势期间只有本组件的临时位移（与辅助线同步），不改 left/top、不改 props。
  if (g && g.kind === 'move' && g.index === idxOf(el, i)) {
    style.transform = `translate(${pxX(g.live.dx)}px, ${pxY(g.live.dy)}px)`
    style.zIndex = String(list.value.length + 10)
  }
  return style
}
/** 命中盒：几何盒与 8px 下限的并集（居中外扩，视觉盒尺寸不变） */
function hitStyle(el, box) {
  const db = dbOf(el) || box
  const bw = pxX(box.w)
  const bh = pxY(box.h)
  const hw = Math.max(bw, HIT_MIN_PX)
  const hh = Math.max(bh, HIT_MIN_PX)
  const inGroup = box !== db
  const baseLeft = inGroup ? pxX(box.x - db.x) : 0
  const baseTop = inGroup ? pxY(box.y - db.y) : 0
  return {
    left: baseLeft - (hw - bw) / 2 + 'px',
    top: baseTop - (hh - bh) / 2 + 'px',
    width: hw + 'px',
    height: hh + 'px'
  }
}
function visualStyle(el, box) {
  const bw = pxX(box.w)
  const bh = pxY(box.h)
  const hw = Math.max(bw, HIT_MIN_PX)
  const hh = Math.max(bh, HIT_MIN_PX)
  // 视觉盒只在自己的命中盒里居中；命中盒本身已经带 segment 相对组的偏移，不能重复加一次。
  return {
    left: (hw - bw) / 2 + 'px',
    top: (hh - bh) / 2 + 'px',
    width: bw + 'px',
    height: bh + 'px'
  }
}
/** 字号 = 盒高换算值；宽度校准只按实际渲染量修正，不重写布局 */
function textStyle(seg) {
  return { fontSize: pxY(seg.h) + 'px', lineHeight: pxY(seg.h) + 'px' }
}
function hintOf(el) {
  const db = dbOf(el)
  if (!db) return ''
  if (db.estimated) return db.hint || EST_HINT
  return ''
}
function hintStyle(el, line) {
  const db = dbOf(el) || { w: 0, h: 0 }
  const l = Number(line) || 0
  return { left: '0px', top: pxY(db.h) + 2 + l * 14 + 'px' }
}

/* ---------- Step 3：pointer 局部手势 + 吸附（只在手势结束提交一次） ---------- */
const gesture = ref(null)

const isDragging = (el, i) => !!(gesture.value && gesture.value.kind === 'move' && gesture.value.index === idxOf(el, i))

/**
 * 吸附候选边（pre-flight §4）：纸的 0/widthDots/heightDots + 其他元素的 displayBounds。
 * order = 稳定元素顺序（绘制序 = 数组下标）；纸边记 -2/-1，同距离时纸边先胜。
 */
function snapCandidates(axis, selfIndex) {
  const out =
    axis === 'x'
      ? [{ v: 0, order: -2, src: 'paper-left' }, { v: props.widthDots, order: -1, src: 'paper-right' }]
      : [{ v: 0, order: -2, src: 'paper-top' }, { v: props.heightDots, order: -1, src: 'paper-bottom' }]
  list.value.forEach((el, i) => {
    if (idxOf(el, i) === selfIndex) return
    const db = dbOf(el)
    if (!db) return
    if (axis === 'x') {
      out.push({ v: db.x, order: i, src: `e${idxOf(el, i)}.left` }, { v: db.x + db.w, order: i, src: `e${idxOf(el, i)}.right` })
    } else {
      out.push({ v: db.y, order: i, src: `e${idxOf(el, i)}.top` }, { v: db.y + db.h, order: i, src: `e${idxOf(el, i)}.bottom` })
    }
  })
  return out
}

/** 每轴只在 ≤8 点内取最近边；同距离按稳定元素顺序决胜。 */
function snapAxis(axis, selfIndex, db, d) {
  const start = axis === 'x' ? db.x : db.y
  const size = axis === 'x' ? db.w : db.h
  const edges = [
    { pos: start + d, which: 'start' },
    { pos: start + d + size, which: 'end' }
  ]
  let best = null
  for (const c of snapCandidates(axis, selfIndex)) {
    for (const e of edges) {
      const dist = Math.abs(e.pos - c.v)
      if (dist > SNAP_DOTS) continue
      const cand = { dist, src: c.src, order: c.order, which: e.which, dots: c.v, snapped: d + (c.v - e.pos) }
      if (!best || dist < best.dist || (dist === best.dist && cand.order < best.order)) best = cand
    }
  }
  return best
}

/** 位移评估：先整数化点值，Shift 时先做 8 点步进；吸附若破坏步进则步进优先（辅助线随之消失）。 */
function evaluateDelta(db, selfIndex, dxPx, dyPx, shift) {
  let dx = toDotDelta(dxPx)
  let dy = toDotDelta(dyPx)
  if (shift) {
    dx = Math.round(dx / STEP_DOTS) * STEP_DOTS
    dy = Math.round(dy / STEP_DOTS) * STEP_DOTS
  }
  const sx = snapAxis('x', selfIndex, db, dx)
  const sy = snapAxis('y', selfIndex, db, dy)
  let fdx = sx ? sx.snapped : dx
  let fdy = sy ? sy.snapped : dy
  let guideX = sx ? { src: sx.src, dots: sx.dots, which: sx.which } : null
  let guideY = sy ? { src: sy.src, dots: sy.dots, which: sy.which } : null
  if (shift) {
    if (sx && fdx % STEP_DOTS !== 0) {
      fdx = dx
      guideX = null
    }
    if (sy && fdy % STEP_DOTS !== 0) {
      fdy = dy
      guideY = null
    }
  }
  return { dx: fdx, dy: fdy, guideX, guideY }
}

function capture(e) {
  const t = e.currentTarget
  try {
    if (t && t.setPointerCapture) t.setPointerCapture(e.pointerId)
  } catch (err) {
    /* 捕获失败（指针已释放等）不阻塞手势：后续用 event 坐标继续 */
  }
}

function onGroupDown(el, i, e) {
  const db = dbOf(el)
  if (!db || !pxPerDot.value) return
  if (e.pointerType === 'mouse' && e.button !== 0) return
  const index = idxOf(el, i)
  emit('select', index)
  capture(e)
  gesture.value = {
    kind: 'move',
    index,
    db: { x: db.x, y: db.y, w: db.w, h: db.h },
    pointerId: e.pointerId,
    startX: e.clientX,
    startY: e.clientY,
    movedPx: 0,
    live: { dx: 0, dy: 0 },
    guideX: null,
    guideY: null,
    handle: null
  }
  if (rootRef.value && rootRef.value.focus) rootRef.value.focus({ preventScroll: true })
}

function onHandleDown(el, i, h, e) {
  const db = dbOf(el)
  if (!db || !pxPerDot.value) return
  if (e.pointerType === 'mouse' && e.button !== 0) return
  const index = idxOf(el, i)
  if (index !== props.selectedIndex) emit('select', index)
  capture(e)
  gesture.value = {
    kind: 'resize',
    index,
    handle: h,
    db: { x: db.x, y: db.y, w: db.w, h: db.h },
    pointerId: e.pointerId,
    startX: e.clientX,
    startY: e.clientY,
    movedPx: 0,
    live: { dx: 0, dy: 0 },
    guideX: null,
    guideY: null
  }
}

/**
 * 位移一律用 clientX/clientY 差值：页面/画布滚动会同时移动元素与指针，
 * 用 pageX（含 scroll 量）就会把滚动量算成标签位移，这里从根上避免。
 */
function onGestureMove(el, i, e) {
  const g = gesture.value
  if (!g || g.pointerId !== e.pointerId) return
  const dxPx = e.clientX - g.startX
  const dyPx = e.clientY - g.startY
  g.movedPx = Math.max(g.movedPx, Math.abs(dxPx), Math.abs(dyPx))
  if (g.kind === 'move') {
    const r = evaluateDelta(g.db, g.index, dxPx, dyPx, e.shiftKey)
    g.live = { dx: r.dx, dy: r.dy }
    g.guideX = r.guideX
    g.guideY = r.guideY
  } else {
    let ddx = toDotDelta(dxPx)
    let ddy = toDotDelta(dyPx)
    if (e.shiftKey) {
      ddx = Math.round(ddx / STEP_DOTS) * STEP_DOTS
      ddy = Math.round(ddy / STEP_DOTS) * STEP_DOTS
    }
    if (g.handle === 'e') ddy = 0
    else if (g.handle === 's') ddx = 0
    else if (g.handle === 'sq') {
      const d = Math.abs(ddx) >= Math.abs(ddy) ? ddx : ddy
      ddx = d
      ddy = d
    }
    g.live = { dx: ddx, dy: ddy }
    g.guideX = null
    g.guideY = null
  }
}

function onGestureUp(el, i, e) {
  const g = gesture.value
  if (!g || g.pointerId !== e.pointerId) return
  gesture.value = null
  const dx = g.live.dx
  const dy = g.live.dy
  // 纯点击（没有行程、位移为 0）不提交；吸附后为 0 的真实拖拽仍提交（用例 5c）。
  if (g.movedPx < MOVE_EPS_PX && dx === 0 && dy === 0) return
  if (g.kind === 'move') emit('move', { index: g.index, dx, dy })
  else emit('resize', { index: g.index, deltaX: dx, deltaY: dy })
}

/** pointercancel / 指针捕获丢失 / 失焦 / 页面隐藏 ⇒ 丢弃未提交手势，不发事件、临时位移复位。 */
function onGestureDiscard(el, i, e) {
  const g = gesture.value
  if (!g) return
  if (e && typeof e.pointerId === 'number' && e.pointerId !== g.pointerId) return
  gesture.value = null
}

function discardAllGestures() {
  if (gesture.value) gesture.value = null
}
function onVisibilityChange() {
  if (document.visibilityState !== 'visible') discardAllGestures()
}
/** 焦点真的离开画布（relatedTarget 为空或不在画布内）⇒ 丢弃未提交手势；画布内部挪焦点不算。 */
function onRootFocusOut(e) {
  if (!gesture.value) return
  const next = e.relatedTarget
  if (next && rootRef.value && rootRef.value.contains(next)) return
  discardAllGestures()
}

const guideX = computed(() => {
  const g = gesture.value
  if (!g || !g.guideX) return null
  return { px: pxX(g.guideX.dots), src: g.guideX.src, dots: g.guideX.dots, which: g.guideX.which }
})
const guideY = computed(() => {
  const g = gesture.value
  if (!g || !g.guideY) return null
  return { px: pxY(g.guideY.dots), src: g.guideY.src, dots: g.guideY.dots, which: g.guideY.which }
})
const resizeOutline = computed(() => {
  const g = gesture.value
  if (!g || g.kind !== 'resize') return null
  return {
    left: pxX(g.db.x) + 'px',
    top: pxY(g.db.y) + 'px',
    width: pxX(Math.max(0, g.db.w + g.live.dx)) + 'px',
    height: pxY(Math.max(0, g.db.h + g.live.dy)) + 'px'
  }
})

/* ---------- Step 5：手柄按 type 决定，档位规则留给 Task 10 ---------- */
function handlesFor(el, i) {
  const db = dbOf(el)
  if (!db) return []
  const index = idxOf(el, i)
  const g = gesture.value
  // 缩放手势期间必须保留被捕获的那个手柄（否则指针捕获随节点卸载失效，拖不动）；
  // 拖动位移时不显示手柄，其它元素的手柄也不显示。
  if (g && (g.kind === 'move' || g.index !== index)) return []
  if (index !== props.selectedIndex) return []
  switch (el.type) {
    case 'qrcode':
      return ['sq'] // 同一个方形柄：两轴同值
    case 'barcode':
      return ['s'] // 只纵向
    case 'text':
    case 'line':
    case 'rect':
      return ['e', 's', 'se']
    default:
      return ['e', 's', 'se']
  }
}
const handleAxis = h => (h === 'e' ? 'x' : h === 's' ? 'y' : 'xy')

/* ---------- Step 4：键盘（先做输入门禁；撤销/重做/保存归容器） ---------- */
const EDITABLE_SELECTOR =
  'input, textarea, select, [contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], .el-input__inner, .el-textarea__inner, .el-input__wrapper, .el-select__input, .el-select-dropdown__item, .el-checkbox__input, .el-radio__input'

function isEditableTarget(target) {
  if (!target || typeof target.closest !== 'function') return false
  return !!target.closest(EDITABLE_SELECTOR)
}

function nudge(dx, dy) {
  if (props.selectedIndex < 0) return
  emit('move', { index: props.selectedIndex, dx, dy })
}

/** Tab 循环：正向下一个、反向上一个，到端点回绕；没有选中时正向从第一个、反向从最后一个开始。 */
function cycleSelect(forward) {
  const n = list.value.length
  if (!n) return
  const orders = list.value.map((el, i) => idxOf(el, i))
  const pos = orders.indexOf(props.selectedIndex)
  let next
  if (pos < 0) next = orders[forward ? 0 : n - 1]
  else next = orders[(pos + (forward ? 1 : -1) + n) % n]
  if (typeof next === 'number') emit('select', next)
}

function onRootKeydown(e) {
  if (isEditableTarget(e.target)) return // 输入框里方向键/Del/Tab 照常编辑，画布不截获
  if (gesture.value) return
  if (e.ctrlKey || e.metaKey || e.altKey) return // Ctrl/Cmd+Z、Shift+Z、S 由容器捕获
  const step = e.shiftKey ? STEP_DOTS : 1
  switch (e.key) {
    case 'ArrowLeft':
      e.preventDefault()
      nudge(-step, 0)
      break
    case 'ArrowRight':
      e.preventDefault()
      nudge(step, 0)
      break
    case 'ArrowUp':
      e.preventDefault()
      nudge(0, -step)
      break
    case 'ArrowDown':
      e.preventDefault()
      nudge(0, step)
      break
    case 'Delete':
      if (props.selectedIndex < 0) return
      e.preventDefault()
      emit('delete', props.selectedIndex)
      break
    case 'Tab':
      if (!list.value.length) return // 空画布不劫持焦点流转
      e.preventDefault()
      cycleSelect(!e.shiftKey)
      break
    default:
      break
  }
}

/* ---------- 字体宽度校准（只测量，不改盒子；观察器 unmount 清理） ---------- */
const rootRef = ref(null)
const calibCount = ref(0)
let ro = null
let fontsListener = null
let disposed = false

function calibrate() {
  if (disposed) return
  const root = rootRef.value
  if (!root) return
  const spans = root.querySelectorAll('[data-cal="seg"]')
  spans.forEach(span => {
    span.style.transform = '' // 先清掉旧 transform 再测
    const target = Number(span.getAttribute('data-target-w'))
    if (!Number.isFinite(target) || target <= 0) {
      span.setAttribute('data-scale-x', 'skip:target<=0')
      return
    }
    if (!span.textContent) {
      span.setAttribute('data-scale-x', 'skip:empty')
      return
    }
    const measured = span.getBoundingClientRect().width
    if (!Number.isFinite(measured) || measured <= 0) {
      span.setAttribute('data-scale-x', 'skip:measured<=0')
      return
    }
    const ratio = target / measured
    if (Math.abs(ratio - 1) > 0.02) {
      span.style.transform = `scaleX(${ratio})`
      span.setAttribute('data-scale-x', ratio.toFixed(4))
    } else {
      span.setAttribute('data-scale-x', '1')
    }
  })
  calibCount.value = calibCount.value + 1
}

onMounted(() => {
  nextTick(calibrate)
  window.addEventListener('blur', discardAllGestures)
  document.addEventListener('visibilitychange', onVisibilityChange)
  if (typeof document !== 'undefined' && document.fonts) {
    if (document.fonts.ready && typeof document.fonts.ready.then === 'function') {
      document.fonts.ready.then(() => calibrate()).catch(() => {})
    }
    if (typeof document.fonts.addEventListener === 'function') {
      fontsListener = () => calibrate()
      document.fonts.addEventListener('loadingdone', fontsListener)
    }
  }
  if (typeof ResizeObserver !== 'function' || !rootRef.value) return
  ro = new ResizeObserver(() => nextTick(calibrate))
  ro.observe(rootRef.value)
})

// 内容或 zoom 变化后 nextTick 重测
watch(
  () => [props.elements, props.zoom, props.widthDots, props.heightDots, props.widthMm, props.heightMm],
  () => nextTick(calibrate),
  { deep: true }
)

watch(ratioOk, ok => {
  if (!ok && import.meta.env.DEV) {
    console.warn('[LabelCanvas] heightDots/heightMm 与 widthDots/widthMm 比例不一致', pxPerDot.value, pxPerDotY.value)
  }
})

onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('blur', discardAllGestures)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (ro) { ro.disconnect(); ro = null }
  if (fontsListener && document.fonts && typeof document.fonts.removeEventListener === 'function') {
    document.fonts.removeEventListener('loadingdone', fontsListener)
    fontsListener = null
  }
})
</script>

<style scoped>
.lc-root { position: relative; outline: none; -webkit-user-drag: none; }
.lc-root:focus .lc-paper { border-color: #409eff; }
.lc-ratio { color: #f56c6c; font-size: 12px; margin-bottom: 6px; }
.lc-scroll { overflow: auto; max-width: 100%; padding: 4px; }
/* 边框不计入内容尺寸：content-box + 显式内容宽高 */
.lc-paper {
  position: relative;
  box-sizing: content-box;
  background: #fff;
  border: 1px solid #c8c9cc;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}
.lc-content { position: relative; overflow: visible; }
.lc-group { position: absolute; box-sizing: content-box; touch-action: none; user-select: none; }
.lc-group.is-selected { outline: 1px dashed #409eff; outline-offset: 1px; }
.lc-group.is-dragging { outline: 1px solid #409eff; outline-offset: 1px; }
.lc-seg { position: absolute; box-sizing: content-box; }
/* 盒子描边用 outline（不计入尺寸）：getBoundingClientRect 恒等于服务端点值换算出的几何 */
.lc-seg-visual {
  position: absolute;
  box-sizing: content-box;
  overflow: hidden;
  outline: 1px solid rgba(144, 147, 153, 0.55);
  outline-offset: -1px;
}
.lc-group.is-selected .lc-seg-visual { outline-color: rgba(64, 158, 255, 0.75); }
.lc-proxy .lc-seg-visual { outline-style: dashed; }
.lc-seg-text {
  display: inline-block;
  white-space: nowrap;
  transform-origin: left top;
  color: #303133;
  max-width: none;
}
/* 粗体：轻微重复绘制 ≈ 打印 x+1，不据 CSS font-weight 改盒子宽度 */
.lc-seg-text.is-bold { text-shadow: 0.4px 0 0 currentColor; }
.lc-img { display: block; width: 100%; height: 100%; object-fit: fill; -webkit-user-drag: none; pointer-events: none; }
/* 缩放手柄：8px 见方，整体偏在元素外侧（不压住元素自身，避免"点本体却开始缩放"） */
.lc-handle {
  position: absolute;
  width: 8px;
  height: 8px;
  margin: -4px 0 0 -4px;
  background: #fff;
  border: 1px solid #409eff;
  box-sizing: border-box;
  z-index: 3;
  touch-action: none;
}
.lc-handle--e { left: calc(100% + 8px); top: 50%; cursor: ew-resize; }
.lc-handle--s { left: 50%; top: calc(100% + 8px); cursor: ns-resize; }
.lc-handle--se { left: calc(100% + 8px); top: calc(100% + 8px); cursor: nwse-resize; }
.lc-handle--sq { left: calc(100% + 8px); top: calc(100% + 8px); cursor: nwse-resize; border-radius: 4px; }
/* 吸附辅助线：与临时位移同步显示，手势结束即消失 */
.lc-guide { position: absolute; z-index: 4; pointer-events: none; background: #f78fa2; }
.lc-guide--x { top: 0; bottom: 0; width: 1px; }
.lc-guide--y { left: 0; right: 0; height: 1px; }
/* 缩放手势中的选择外框（虚线），不动任何真实盒子 */
.lc-resize-outline {
  position: absolute;
  z-index: 5;
  pointer-events: none;
  box-sizing: border-box;
  border: 1px dashed #409eff;
  background: rgba(64, 158, 255, 0.08);
}
.lc-hint {
  position: absolute;
  left: 0;
  white-space: nowrap;
  font-size: 11px;
  line-height: 12px;
  color: #e6a23c;
  pointer-events: none;
  z-index: 2;
}
.lc-hint--missing { color: #f56c6c; }
.lc-blank { color: #909399; font-size: 12px; padding: 6px 2px; }
</style>
