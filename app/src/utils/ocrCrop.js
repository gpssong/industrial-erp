// v1.1.177 原料标签「框选识别区域」的几何换算 + 画布裁剪。
//
// rect 一律是「相对这张图显示出来之后」的归一化坐标 { x, y, w, h } ∈ [0,1]：
// 预览框宽度随屏幕变，裁剪要落回原图像素，所以两侧都用归一化，只在这里换一次算。
//
// 为什么在客户端裁而不是把矩形坐标发给后端：浏览器 drawImage 之前已按 EXIF 把图转正，
// 服务端 ImageIO 不认 EXIF（旋转过的照片会裁偏），也不认 WebP/HEIC；
// 且本地图是 blob URL（同源），画布不会被 crossOrigin 污染，toBlob 拿得到数据 ——
// 换成已上传的服务端 URL 就在 Capacitor APK 里属于跨源，画布直接脏掉导不出。

/** 长边超过这个值就等比缩到它：批号区域放大后识别不吃亏，又能把裁后图压到几百 KB */
export const MAX_CROP_EDGE = 1200

/** 判定「等于没框」的容差 */
export const FULL_FRAME_TOL = 0.02

/** 框选 UI 允许的最小边（归一化） */
export const MIN_EDGE_RATIO = 0.05

export function clampRect(rect) {
  if (!rect) return null
  const x = Math.min(Math.max(Number(rect.x) || 0, 0), 1)
  const y = Math.min(Math.max(Number(rect.y) || 0, 0), 1)
  const w = Math.min(Math.max(Number(rect.w) || 0, 0), 1 - x)
  const h = Math.min(Math.max(Number(rect.h) || 0, 0), 1 - y)
  return { x, y, w, h }
}

/** 没框、或框基本盖住整张图 —— 这两种都按整张识别，不必再裁 */
export function isFullFrame(rect, tol = FULL_FRAME_TOL) {
  if (!rect) return true
  const r = clampRect(rect)
  return r.x <= tol && r.y <= tol && r.w >= 1 - tol && r.h >= 1 - tol
}

/**
 * 归一化框 → 画布用的源矩形与目标矩形。
 * 空框（w 或 h 为 0，例如手指点了一下没拖）退化成从该点起的整块剩余区域，
 * 保证永远能裁出至少 1px，而不是把 0 宽矩形喂给 drawImage。
 */
export function rectToPixels(rect, naturalW, naturalH, maxEdge = MAX_CROP_EDGE) {
  const w0 = Math.floor(Number(naturalW))
  const h0 = Math.floor(Number(naturalH))
  if (!(w0 > 0 && h0 > 0)) return null
  const r = clampRect(rect) || { x: 0, y: 0, w: 1, h: 1 }
  const fw = r.w > 0 ? r.w : 1 - r.x
  const fh = r.h > 0 ? r.h : 1 - r.y
  const sx = Math.min(Math.max(Math.floor(r.x * w0), 0), w0 - 1)
  const sy = Math.min(Math.max(Math.floor(r.y * h0), 0), h0 - 1)
  const sw = Math.min(Math.max(Math.round(fw * w0), 1), w0 - sx)
  const sh = Math.min(Math.max(Math.round(fh * h0), 1), h0 - sy)
  const scale = Math.min(1, maxEdge / Math.max(sw, sh))
  return {
    sx,
    sy,
    sw,
    sh,
    dw: Math.max(1, Math.round(sw * scale)),
    dh: Math.max(1, Math.round(sh * scale))
  }
}

/**
 * 两个 boundingClientRect (视口坐标) → 图片真正渲染出来的那个盒, 换算成相对 stage 的偏移。
 * widthFix 下横图会比 flex:1 的 stage 矮一大截, 框选只能以这个盒为基准,
 * 否则归一化值喂给 rectToPixels 时会按容器高放大, 裁到图外的空白处。
 */
export function imageBox(imgRect, stageRect) {
  if (!imgRect || !stageRect) return null
  const w = Number(imgRect.width)
  const h = Number(imgRect.height)
  if (!(w > 0) || !(h > 0)) return null
  return {
    left: Number(imgRect.left) - Number(stageRect.left),
    top: Number(imgRect.top) - Number(stageRect.top),
    width: w,
    height: h
  }
}

/** 归一化 rect (相对图片渲染盒) → 相对 stage 的 px 定位, 给 .rc-box 的 style 用 */
export function rectToStyle(rect, box) {
  const r = clampRect(rect) || { x: 0, y: 0, w: 1, h: 1 }
  return {
    left: Math.round(box.left + r.x * box.width) + 'px',
    top: Math.round(box.top + r.y * box.height) + 'px',
    width: Math.round(r.w * box.width) + 'px',
    height: Math.round(r.h * box.height) + 'px'
  }
}

/** 读本地临时图为 <img>（不走服务端 URL，见文件头注释） */
export function loadImageElement(src) {
  return new Promise((resolve, reject) => {
    if (typeof Image === 'undefined') {
      reject(new Error('当前环境不支持图片裁剪'))
      return
    }
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('图片读取失败，无法框选'))
    img.src = src
  })
}

function canvasToJpegBlob(canvas, quality) {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('裁剪导出失败'))),
      'image/jpeg',
      quality
    )
  })
}

/**
 * 按框裁出一张 jpg。返回 File（带 .jpg 文件名，后端按扩展名白名单校验）。
 * @param {string} src   uni.chooseImage 拿到的本地 tempFilePath
 * @param {object} rect  归一化 { x, y, w, h }
 * @param {object} [opts] { maxEdge, quality, name }
 */
export async function cropToJpegFile(src, rect, opts = {}) {
  if (typeof document === 'undefined') {
    throw new Error('当前环境不支持图片裁剪')
  }
  const img = await loadImageElement(src)
  const box = rectToPixels(rect, img.naturalWidth || img.width, img.naturalHeight || img.height, opts.maxEdge)
  if (!box) throw new Error('取不到原图尺寸，无法裁剪')
  const canvas = document.createElement('canvas')
  canvas.width = box.dw
  canvas.height = box.dh
  const ctx = canvas.getContext('2d')
  ctx.drawImage(img, box.sx, box.sy, box.sw, box.sh, 0, 0, box.dw, box.dh)
  const blob = await canvasToJpegBlob(canvas, opts.quality || 0.85)
  const name = (opts.name || 'label-crop') + '-' + Date.now() + '.jpg'
  return new File([blob], name, { type: 'image/jpeg' })
}
