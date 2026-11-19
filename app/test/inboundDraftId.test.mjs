// v1.1.202 回归护栏: 入库页 ensureDraft 必须用「新建接口回传的单 id」,
// 不能再拿**工单号**当入库单号去列表里反查 —— 入库单单号是 PFI/PSI 另生成的。
//
// 事故: App「审核入库」一步完成流程里, ensureDraft() 建完草稿后靠
// GET /production/finished-in/page?billNo={工单号} 回捞 —— 工单号 PD2026… 与入库单号 PFI2026…
// 不是一个号 ⇒ 列表查不到 ⇒ draftId 为空 ⇒ 现场报「入库单草稿保存失败，请重试」。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../src')

const pages = [
  { rel: 'pages/scan/finished-in.vue', add: 'prdFinishedInAdd', page: 'prdFinishedInPage' },
  { rel: 'pages/scan/semi-in.vue', add: 'prdSemiInAdd', page: 'prdSemiInPage' }
]

test('入库页 ensureDraft 用新建接口回传的单 id', () => {
  for (const { rel, add } of pages) {
    const src = readFileSync(resolve(root, rel), 'utf8')
    const start = src.indexOf('async ensureDraft()')
    assert.ok(start > 0, `${rel}: 找不到 ensureDraft()`)
    const body = src.slice(start, start + 2000)
    assert.ok(body.includes(`const createdId = await api.${add}(`),
      `${rel}: ensureDraft 未取新建接口回传的 id`)
    assert.ok(/if \(createdId\)[\s\S]*this\.draftId = String\(createdId\)/.test(body),
      `${rel}: ensureDraft 未把回传的 id 写进 draftId`)
  }
})

test('入库页 ensureDraft 不得再用工单号反查入库单', () => {
  for (const { rel, page } of pages) {
    const src = readFileSync(resolve(root, rel), 'utf8')
    const start = src.indexOf('async ensureDraft()')
    const body = src.slice(start, start + 2000)
    assert.ok(!/billNo:\s*o\.billNo/.test(body),
      `${rel}: ensureDraft 仍在用「工单号」当入库单号反查 (${page})`)
    assert.ok(/prdOrderId/.test(body),
      `${rel}: 兜底查询应按 prdOrderId 关联找`)
  }
})
