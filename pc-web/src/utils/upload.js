/**
 * 图片上传选择器 —— 隐藏 input 取一张图 → /system/upload/file → 回传 URL。
 * 之前每个页面各写一遍 "createElement('input') + onchange + uploadFile"。
 */

import { ElMessage } from 'element-plus'
import { uploadFile } from '@/api/workflow'

/** 选一张图片并上传, 成功后回调 (url, fileName)。 */
export function pickImageFile(cb) {
  const inp = document.createElement('input')
  inp.type = 'file'; inp.accept = 'image/*'
  inp.onchange = async () => {
    const f = inp.files && inp.files[0]
    if (!f) return
    try {
      const url = await uploadFile(f)
      cb(url, f.name)
      ElMessage.success('照片已上传')
    } catch (e) { ElMessage.error((e && e.message) || '上传失败') }
  }
  inp.click()
}
