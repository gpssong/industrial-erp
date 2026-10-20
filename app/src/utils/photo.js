// v1.1.106 XK 照片上传 — 3 个 XK 页面的公共逻辑
//
// 背景: xk/material、xk/safety、xk/print 三页原本都用 unsplash 外链当占位图,
//   点击"拍照/上传" 只会往数组里再塞一个外链 (onAddPhoto 甚至直接写死字符串),
//   用户拍的真照片从来没进过后端 —— 而后端字段 (PurReceipt.labelPhotos /
//   XkSafetyCheck.reformPhotosBefore/After) 一直是存 URL 数组的, 只是前端没传。
//
// 本模块把「选图 → 上传 /system/upload/file → 拿回 URL → 拼成 JSON 字符串」收口,
// 三个页面共用 (对应 pc-web 已接好的 /system/upload/file 通道)。
import { pickAndUploadImage } from './upload.js'

/** 单张照片最大张数 (后端 XkSafetyCheck 注释: 最多 6 张) */
export const MAX_PHOTOS = 6

/** 逗号分隔的 URL 数组 → JSON 字符串 (后端字段类型是 JSON 数组) */
export function photosToJson(urls) {
  const list = (urls || []).filter(Boolean)
  return list.length ? JSON.stringify(list) : null
}

/** 后端 JSON 字符串 → URL 数组 (容错: 也接受逗号分隔的旧格式) */
export function photosFromJson(text) {
  if (!text) return []
  if (typeof text === 'object') return Array.isArray(text) ? text : []
  const s = String(text).trim()
  if (!s) return []
  if (s.startsWith('[')) {
    try {
      const arr = JSON.parse(s)
      return Array.isArray(arr) ? arr.filter(Boolean) : []
    } catch (e) { return [] }
  }
  // 兼容逗号分隔
  return s.split(',').map(x => x.trim()).filter(Boolean)
}

/**
 * 选图并上传, 成功后返回后端 URL (失败抛异常, 由调用方提示)
 * @param {object} [opts] 透传给 pickAndUploadImage (count / sourceType)
 * @returns {Promise<string>} 后端返回的文件 URL
 */
export async function pickAndUploadPhoto(opts = {}) {
  const r = await pickAndUploadImage(opts)
  if (!r || !r.url) throw new Error('上传未返回文件地址')
  return r.url
}

/**
 * 往数组里追加一张已上传的照片 (超上限自动拦)
 * @param {string[]} list 目标数组 (原地 push)
 * @returns {Promise<boolean>} true=成功追加, false=已满或失败 (失败已弹 toast)
 */
export async function addPhotoTo(list, opts = {}) {
  if ((list || []).length >= MAX_PHOTOS) {
    uni.showToast({ title: `最多 ${MAX_PHOTOS} 张`, icon: 'none' })
    return false
  }
  if (typeof uni !== 'undefined' && uni.showLoading) {
    uni.showLoading({ title: '上传中...', mask: true })
  }
  try {
    const url = await pickAndUploadPhoto(opts)
    list.push(url)
    return true
  } catch (e) {
    const msg = (e && (e.msg || e.message)) || '照片上传失败'
    uni.showToast({ title: msg, icon: 'none', duration: 2500 })
    return false
  } finally {
    if (typeof uni !== 'undefined' && uni.hideLoading) uni.hideLoading()
  }
}