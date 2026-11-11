// 原料入库「识别批号」编排 (v1.1.171 起, v1.1.177 加框选识别区域):
//   拍照 → [在本地临时图上框选要识别的区域] → 上传原图 → 落成凭证照片 → 只对刚拍的这张按品名定向识别批号。
// 框了区域时另外上传一张裁后小图，OCR 只吃这张：多原料同框的标签不再互相串批号。
// 抽成注入依赖的纯函数以便 node:test 单测 (对齐 uploadHeaders.test.mjs);
// 页面只做适配，把 this / API / uni 接进来。
// 即使未配 OCR key 也照常拍照+保存原图 (XK 至少 1 张原始标签照片), 取消拍照/取消框选静默。
import { isFullFrame } from './ocrCrop.js'

const PHOTO_TYPES = ['LABEL_PHOTO', 'PACK_PHOTO', 'SAMPLE_PHOTO']

/** 候选条最多给 6 个, 再多在小屏上没法点 */
const MAX_CANDIDATES = 6

/**
 * 共享内核：选图 → 框选 → 上传原图 → 交回页面落凭证 → 定向识别 → 回填。
 * 原图怎么落成「单据的凭证」两个页不一样 (IQC 附件 vs labelPhotos URL 数组)，
 * 所以这一步是注入的 saveOriginal，内核不碰页面状态。
 *
 * @param {object} ctx {
 *   ocrEnabled: boolean,
 *   pickImage(): Promise<{tempFilePath}>,
 *   uploadImage(path): Promise<{url, filename, size, contentType}>,
 *   pickRegion?(path): Promise<object|null>,          // null = 整张; reject {errMsg:'...cancel'} = 用户取消
 *   cropAndUpload?(path, rect): Promise<{url}>,       // 裁后小图另存一张
 *   saveOriginal(original): Promise<void>,            // 原图落凭证 (XK 证据必须是整张)
 *   ocrLabelBatchNo(urls, productName),
 *   toast(title, duration)
 * }
 * @param {object} m 目标行 { productName, batchNo } — 回填 batchNo 与 ocrCandidates
 */
export async function captureRegionAndRecognize(ctx, m) {
  try {
    const picked = await ctx.pickImage()
    const tempFilePath = picked && picked.tempFilePath
    if (!tempFilePath) throw new Error('未选择文件')
    // 没配 OCR key 就别让人白框一次 (原图仍要拍照落凭证, 所以下面照常上传)
    const rect = ctx.ocrEnabled && ctx.pickRegion ? await ctx.pickRegion(tempFilePath) : null
    const original = await ctx.uploadImage(tempFilePath)
    await ctx.saveOriginal(original)
    if (!ctx.ocrEnabled) {
      ctx.toast('照片已保存；未配置视觉模型，批号请手工录入')
      return
    }
    // 只识别刚拍的这张: TextIn 按张计费更省, 按品名定向也不会被旧照片干扰
    const ocrUrl = await resolveOcrUrl(ctx, tempFilePath, original, rect)
    const resp = await ctx.ocrLabelBatchNo([ocrUrl], m && m.productName)
    // 后端 500 (OCR 未配置 / TextIn 并发限制 / HTTP 5xx / 照片读不到) 不能被当成「没识别到」,
    // 否则用户以为是照片拍糊了 —— 真实原因在 resp.msg 里
    if (resp && resp.code === 500) {
      ctx.toast('识别失败：' + (resp.msg || '未知错误'), 4000)
      return
    }
    const cand = ((resp && resp.data && resp.data.candidates) || []).slice(0, MAX_CANDIDATES)
    if (m) m.ocrCandidates = cand
    if (cand.length) {
      if (m) m.batchNo = cand[0]
      ctx.toast(((m && m.productName) || '本行') + '：识别到 ' + cand[0] + '，可点候选条改选', 3000)
    } else {
      ctx.toast('照片已保存；未识别到该原料批号，请手工录入')
    }
  } catch (e) {
    // uni.chooseImage 取消 / 框选弹窗取消都带 cancel 字样 — 静默, 不弹错误
    if (e && /cancel/i.test((e.errMsg || e.message || ''))) return
    ctx.toast('识别失败：' + ((e && (e.msg || e.message)) || '未知错误'))
  }
}

/** 有有效框就裁一张去识别；裁剪或裁后图上传失败时回落整张，不因此把识别整体打断 */
async function resolveOcrUrl(ctx, tempFilePath, original, rect) {
  if (!rect || isFullFrame(rect) || !ctx.cropAndUpload) return original.url
  try {
    const cropped = await ctx.cropAndUpload(tempFilePath, rect)
    if (cropped && cropped.url) return cropped.url
    ctx.toast('区域图没传上去，改按整张识别')
  } catch (e) {
    ctx.toast('区域裁剪失败，改按整张识别')
  }
  return original.url
}

/**
 * scan/in.vue「到货原料与批号」入口：原图要绑成 IQC 的 LABEL_PHOTO 附件并刷新照片区。
 * @param {object} state 组件可变状态 { iqcId, ocrEnabled, ocrLoadingIdx, attachments, photos }
 * @param {object} deps  { pickImage, uploadImage, pickRegion?, cropAndUpload?,
 *                         bindAttachment, listAttachments, ocrLabelBatchNo, toast }
 * @param {number} i     本行下标 (忙状态标记)
 * @param {object} m     本行原料 { productName, batchNo }
 */
export async function captureAndRecognizeBatch(state, deps, i, m) {
  if (state.ocrLoadingIdx >= 0) return
  state.ocrLoadingIdx = i
  try {
    await captureRegionAndRecognize(
      {
        ocrEnabled: state.ocrEnabled,
        pickImage: deps.pickImage,
        uploadImage: deps.uploadImage,
        pickRegion: deps.pickRegion,
        cropAndUpload: deps.cropAndUpload,
        ocrLabelBatchNo: deps.ocrLabelBatchNo,
        toast: deps.toast,
        saveOriginal: async (original) => {
          await deps.bindAttachment(state.iqcId, {
            fileUrl: original.url,
            fileName: original.filename,
            fileSizeKb: Math.round((original.size || 0) / 1024),
            attachType: 'LABEL_PHOTO'
          })
          const list = await deps.listAttachments(state.iqcId)
          state.attachments = list || []
          state.photos = state.attachments.filter((a) => PHOTO_TYPES.includes(a.attachType))
        }
      },
      m
    )
  } finally {
    state.ocrLoadingIdx = -1
  }
}
