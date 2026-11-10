// 需求: 原料入库「识别批号」一步到位 — 点按钮即拍照 → [本地框选识别区域] → 上传原图存为 LABEL_PHOTO 附件
//   → 刷新下方照片区 → 只对刚拍的这张 (框了区域就是那张裁后小图) 按品名定向识别批号并回填本行。
// 即使未配 OCR key 也照常拍照+保存原图 (满足 XK 至少 1 张原始标签照片); 取消拍照/取消框选静默。
// 编排逻辑抽到 utils/ocrCapture.js 注入依赖单测 (对齐 uploadHeaders.test.mjs);
// 相机/上传/OCR/画布都是外部边界, 用 fake 替身, 被测的是真实编排代码。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { captureAndRecognizeBatch, captureRegionAndRecognize } from '../src/utils/ocrCapture.js'
import { clampRect, isFullFrame, rectToPixels } from '../src/utils/ocrCrop.js'

function makeState(over = {}) {
  return {
    iqcId: 'iqc-1',
    ocrEnabled: true,
    ocrLoadingIdx: -1,
    attachments: [],
    photos: [],
    ...over
  }
}

const RECT = { x: 0.1, y: 0.2, w: 0.5, h: 0.3 }

function makeDeps(over = {}) {
  const calls = { bind: [], list: [], ocr: [], toast: [], pick: [], upload: [], region: [], crop: [] }
  const deps = {
    pickImage: async () => { calls.pick.push(1); return { tempFilePath: 'blob:/tmp/new.jpg' } },
    uploadImage: async (p) => {
      calls.upload.push(p)
      return { url: 'http://x/new.jpg', filename: 'new.jpg', size: 2048 }
    },
    bindAttachment: async (iqcId, payload) => { calls.bind.push({ iqcId, payload }) },
    listAttachments: async (iqcId) => {
      calls.list.push(iqcId)
      return [{ attachType: 'LABEL_PHOTO', fileUrl: 'http://x/new.jpg' }]
    },
    ocrLabelBatchNo: async (urls, hint) => {
      calls.ocr.push({ urls, hint })
      return { data: { candidates: ['BATCH-001'] } }
    },
    toast: (title, duration) => { calls.toast.push({ title, duration }) },
    ...over
  }
  return { deps, calls }
}

test('一步到位: 拍照→存 LABEL_PHOTO 附件→刷新照片→只对新照片识别→回填批号', async () => {
  const state = makeState()
  const { deps, calls } = makeDeps()
  const m = { productName: '赛科0220kj', batchNo: '' }
  await captureAndRecognizeBatch(state, deps, 0, m)

  assert.equal(calls.bind.length, 1, '存了一次附件')
  assert.equal(calls.bind[0].iqcId, 'iqc-1')
  assert.equal(calls.bind[0].payload.attachType, 'LABEL_PHOTO')
  assert.equal(calls.bind[0].payload.fileUrl, 'http://x/new.jpg')
  assert.equal(calls.bind[0].payload.fileSizeKb, 2, '2048B → 2KB')
  assert.deepEqual(calls.list, ['iqc-1'], '刷新了附件列表')
  assert.equal(state.photos.length, 1, '照片区已刷新出新照片')
  assert.equal(state.photos[0].fileUrl, 'http://x/new.jpg')
  // 只对刚拍的这张识别, 不扫旧照片 (TextIn 按张计费且更准)
  assert.equal(calls.ocr.length, 1)
  assert.deepEqual(calls.ocr[0].urls, ['http://x/new.jpg'])
  assert.equal(calls.ocr[0].hint, '赛科0220kj', '按本行品名定向识别')
  assert.equal(m.batchNo, 'BATCH-001', '回填本行批号')
  assert.deepEqual(m.ocrCandidates, ['BATCH-001'], '候选挂到行上供点选')
  assert.equal(state.ocrLoadingIdx, -1, '忙状态复位')
})

test('框了区域: 原图仍整张入附件, 只用裁后小图识别', async () => {
  const state = makeState()
  const { deps, calls } = makeDeps({
    pickRegion: async (src) => { calls.region.push(src); return RECT },
    cropAndUpload: async (src, rect) => {
      calls.crop.push({ src, rect })
      return { url: 'http://x/crop-1.jpg', filename: 'crop-1.jpg', size: 88000 }
    }
  })
  const m = { productName: '扬巴ldpe2426h', batchNo: '' }
  await captureAndRecognizeBatch(state, deps, 0, m)

  assert.deepEqual(calls.region, ['blob:/tmp/new.jpg'], '框选吃的是本地临时图, 不是服务端 URL')
  assert.deepEqual(calls.crop, [{ src: 'blob:/tmp/new.jpg', rect: RECT }], '按框裁了一张')
  assert.equal(calls.bind[0].payload.fileUrl, 'http://x/new.jpg', '附件仍是整张原图 (XK 证据不能只剩局部)')
  assert.deepEqual(calls.ocr[0].urls, ['http://x/crop-1.jpg'], 'OCR 只吃裁后小图')
  assert.equal(m.batchNo, 'BATCH-001')
})

test('没框区域 (整张): 不裁剪, 直接用原图识别', async () => {
  const state = makeState()
  const { deps, calls } = makeDeps({
    pickRegion: async () => null,
    cropAndUpload: async () => { calls.crop.push(1); return { url: 'http://x/crop-1.jpg' } }
  })
  const m = { productName: 'x', batchNo: '' }
  await captureAndRecognizeBatch(state, deps, 0, m)

  assert.equal(calls.crop.length, 0, 'rect=null 不该裁')
  assert.deepEqual(calls.ocr[0].urls, ['http://x/new.jpg'])
})

test('框出来的区域其实盖住整张: 视同整张, 不白裁一刀', async () => {
  const state = makeState()
  const { deps, calls } = makeDeps({
    pickRegion: async () => ({ x: 0, y: 0, w: 1, h: 1 }),
    cropAndUpload: async () => { calls.crop.push(1); return { url: 'http://x/crop-1.jpg' } }
  })
  await captureAndRecognizeBatch(state, deps, 0, { productName: 'x', batchNo: '' })

  assert.equal(calls.crop.length, 0)
  assert.deepEqual(calls.ocr[0].urls, ['http://x/new.jpg'])
})

test('取消框选: 静默收尾, 原图都不上传不落附件', async () => {
  const state = makeState()
  const { deps, calls } = makeDeps({
    pickRegion: async () => { throw { errMsg: 'regionPicker:fail cancel' } }
  })
  const m = { productName: '赛科0220kj', batchNo: 'OLD-7' }
  await captureAndRecognizeBatch(state, deps, 0, m)

  assert.equal(calls.upload.length, 0, '取消后连原图都不必传')
  assert.equal(calls.bind.length, 0)
  assert.equal(calls.ocr.length, 0)
  assert.equal(calls.toast.length, 0, '取消静默')
  assert.equal(m.batchNo, 'OLD-7', '不覆盖已填的批号')
  assert.equal(state.ocrLoadingIdx, -1, '取消也要复位忙状态')
})

test('裁剪失败: 回落整张识别, 不因此把识别打断', async () => {
  const state = makeState()
  const { deps, calls } = makeDeps({
    pickRegion: async () => RECT,
    cropAndUpload: async () => { throw new Error('当前环境不支持图片裁剪') }
  })
  const m = { productName: 'x', batchNo: '' }
  await captureAndRecognizeBatch(state, deps, 0, m)

  assert.deepEqual(calls.ocr[0].urls, ['http://x/new.jpg'], '按整张识别')
  assert.ok(calls.toast.some((t) => /整张/.test(t.title)), '说明回落原因')
  assert.equal(m.batchNo, 'BATCH-001', '仍然回填')
})

test('未配 OCR key: 不弹框选, 仍拍照+保存附件+刷新照片, 不调 OCR, 提示手工录入', async () => {
  const state = makeState({ ocrEnabled: false })
  const { deps, calls } = makeDeps({
    pickRegion: async () => { calls.region.push(1); return RECT }
  })
  const m = { productName: '扬巴ldpe2426h', batchNo: '' }
  await captureAndRecognizeBatch(state, deps, 1, m)

  assert.equal(calls.bind.length, 1, '未配 OCR 也保存附件 (XK 合规)')
  assert.equal(calls.bind[0].payload.attachType, 'LABEL_PHOTO')
  assert.equal(state.photos.length, 1)
  assert.equal(calls.region.length, 0, '未配 OCR 不该让人白框一次')
  assert.equal(calls.ocr.length, 0, '未配 OCR 不调识别接口')
  assert.equal(m.batchNo, '', '不回填')
  assert.ok(calls.toast.some((t) => /手工录入/.test(t.title)), '提示手工录入')
  assert.equal(state.ocrLoadingIdx, -1)
})

test('取消拍照: 静默处理, 不存附件不识别不弹错误, 忙状态复位', async () => {
  const state = makeState()
  const { deps, calls } = makeDeps({
    pickImage: async () => { throw { errMsg: 'chooseImage:fail cancel' } }
  })
  const m = { productName: '赛科0220kj', batchNo: '' }
  await captureAndRecognizeBatch(state, deps, 0, m)

  assert.equal(calls.bind.length, 0, '取消后不存附件')
  assert.equal(calls.ocr.length, 0)
  assert.equal(calls.toast.length, 0, '取消静默, 不弹任何 toast')
  assert.equal(m.batchNo, '')
  assert.equal(state.ocrLoadingIdx, -1, '取消也要复位忙状态')
})

test('忙状态守卫: 已在识别中则直接返回, 不再拍照', async () => {
  const state = makeState({ ocrLoadingIdx: 2 })
  const { deps, calls } = makeDeps()
  const m = { productName: 'x', batchNo: '' }
  await captureAndRecognizeBatch(state, deps, 0, m)

  assert.equal(calls.pick.length, 0, '守卫在最前面, 不该拉相机')
  assert.equal(calls.bind.length, 0)
  assert.equal(calls.ocr.length, 0)
  assert.equal(state.ocrLoadingIdx, 2, '不改动他人的忙状态')
})

test('OCR 无候选: 照片已保存, 提示手工录入, 不回填', async () => {
  const state = makeState()
  const { deps, calls } = makeDeps({
    ocrLabelBatchNo: async () => ({ data: { candidates: [] } })
  })
  const m = { productName: '赛科0220kj', batchNo: '' }
  await captureAndRecognizeBatch(state, deps, 0, m)

  assert.equal(calls.bind.length, 1, '照片仍保存')
  assert.equal(state.photos.length, 1)
  assert.equal(m.batchNo, '')
  assert.deepEqual(m.ocrCandidates, [], '清空候选条')
  assert.ok(calls.toast.some((t) => /手工录入/.test(t.title)))
  assert.equal(state.ocrLoadingIdx, -1)
})

test('识别接口失败 (非取消): 弹失败提示, 照片已保存, 忙状态复位', async () => {
  const state = makeState()
  const { deps, calls } = makeDeps({
    ocrLabelBatchNo: async () => { throw { msg: '视觉模型超时' } }
  })
  const m = { productName: '赛科0220kj', batchNo: '' }
  await captureAndRecognizeBatch(state, deps, 0, m)

  assert.equal(calls.bind.length, 1, '识别失败前照片已保存')
  assert.ok(calls.toast.some((t) => /失败/.test(t.title)), '弹失败提示')
  assert.equal(m.batchNo, '')
  assert.equal(state.ocrLoadingIdx, -1)
})

// ===== xk/material.vue 走的入口: 原图落 form.photos 而不是 IQC 附件 =====

test('material 入口: 拍一张 → 原图 push 进单据照片 → 只识别裁后小图 → 写回批号', async () => {
  const photos = []
  const ocr = []
  const toast = []
  const ctx = {
    ocrEnabled: true,
    pickImage: async () => ({ tempFilePath: 'blob:/tmp/m.jpg' }),
    uploadImage: async () => ({ url: 'http://x/m.jpg', filename: 'm.jpg', size: 4096 }),
    pickRegion: async () => RECT,
    cropAndUpload: async () => ({ url: 'http://x/m-crop.jpg' }),
    saveOriginal: async (o) => { photos.push(o.url) },
    ocrLabelBatchNo: async (urls, hint) => {
      ocr.push({ urls, hint })
      return { data: { candidates: ['LOT-9', 'LOT-9A'] } }
    },
    toast: (t) => toast.push(t)
  }
  const target = { productName: '赛科0220kj', batchNo: '', ocrCandidates: [] }
  await captureRegionAndRecognize(ctx, target)

  assert.deepEqual(photos, ['http://x/m.jpg'], '整张原图进了单据照片 (裁后图不落单)')
  assert.deepEqual(ocr[0].urls, ['http://x/m-crop.jpg'], 'OCR 吃裁后小图')
  assert.equal(ocr[0].hint, '赛科0220kj', '这页按 form.product 定向 — 老代码连品名都没传')
  assert.equal(target.batchNo, 'LOT-9')
  assert.deepEqual(target.ocrCandidates, ['LOT-9', 'LOT-9A'])
})

test('material 入口: 未配 OCR 时照片照样落单并提示去 PC 填 key', async () => {
  const photos = []
  const toast = []
  const ctx = {
    ocrEnabled: false,
    pickImage: async () => ({ tempFilePath: 'blob:/tmp/m.jpg' }),
    uploadImage: async () => ({ url: 'http://x/m.jpg' }),
    saveOriginal: async (o) => { photos.push(o.url) },
    ocrLabelBatchNo: async () => { throw new Error('不该被调用') },
    toast: (t) => toast.push(t)
  }
  await captureRegionAndRecognize(ctx, { productName: 'x', batchNo: '' })

  assert.deepEqual(photos, ['http://x/m.jpg'])
  assert.ok(toast.some((t) => /未配置视觉模型/.test(t)))
})

// ===== ocrCrop 几何换算 (纯函数, 不碰 DOM) =====

test('clampRect: 越界的框收回到图内', () => {
  const c = clampRect({ x: 0.8, y: 0.5, w: 0.5, h: 0.9 })
  assert.equal(c.x, 0.8)
  assert.equal(c.y, 0.5)
  // 1-0.8 在浮点下是 0.19999999999999996, 这里只断"收到右/下边界"这个语义
  assert.ok(Math.abs(c.w - 0.2) < 1e-9, '宽收到图的右边界')
  assert.ok(Math.abs(c.h - 0.5) < 1e-9, '高收到图的下边界')
  assert.deepEqual(clampRect({ x: -0.2, y: 0, w: 2, h: 0 }), { x: 0, y: 0, w: 1, h: 0 })
  assert.equal(clampRect(null), null)
})

test('isFullFrame: 没框 / 盖住整张都算整张', () => {
  assert.equal(isFullFrame(null), true)
  assert.equal(isFullFrame({ x: 0, y: 0, w: 1, h: 1 }), true)
  assert.equal(isFullFrame({ x: 0.001, y: 0.002, w: 0.999, h: 0.999 }), true)
  assert.equal(isFullFrame({ x: 0.1, y: 0.1, w: 0.8, h: 0.8 }), false)
})

test('rectToPixels: 按原图尺寸落像素并把长边缩到上限', () => {
  assert.deepEqual(rectToPixels({ x: 0.5, y: 0.5, w: 0.5, h: 0.5 }, 4000, 3000), {
    sx: 2000, sy: 1500, sw: 2000, sh: 1500, dw: 1200, dh: 900
  })
  // 小图不放大: 原样输出
  assert.deepEqual(rectToPixels({ x: 0, y: 0, w: 1, h: 1 }, 600, 400, 1200), {
    sx: 0, sy: 0, sw: 600, sh: 400, dw: 600, dh: 400
  })
})

test('rectToPixels: 点一下没拖 (w/h=0) 退化成该点起的剩余区域, 不产出 0 宽矩形', () => {
  const r = rectToPixels({ x: 0.5, y: 0.25, w: 0, h: 0 }, 1000, 800)
  assert.equal(r.sx, 500)
  assert.equal(r.sy, 200)
  assert.equal(r.sw, 500)
  assert.equal(r.sh, 600)
  assert.ok(r.dw >= 1 && r.dh >= 1)
})

test('rectToPixels: 拿不到原图尺寸就返回 null (调用方回落整张)', () => {
  assert.equal(rectToPixels({ x: 0, y: 0, w: 1, h: 1 }, 0, 0), null)
  assert.equal(rectToPixels({ x: 0, y: 0, w: 1, h: 1 }, undefined, 3000), null)
})
