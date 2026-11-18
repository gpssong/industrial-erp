// v1.1.201 回归护栏: 「审核入库 / 签合格」类按钮**不得**被 draftId 卡住。
//
// 事故: v1.1.197 给成品入库页(finished-in.vue)加了 ensureDraft() (没草稿就现建再审核),
// 但漏改了按钮的 :disabled 条件 —— 仍是 `!draftId` ⇒ 现场看到「审核入库」永远灰着,
// 又一次误判成 XK 门禁不让入库 (半成品入库页 semi-in.vue 当时已改成 `!orderId`)。
//
// 本护栏做静态断言: 所有「审核/签核」按钮的 disabled 条件里不得出现 draftId。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../src')

// 会签核/放行的按钮 (class 含 btn-check / btn-sign)
const SIGN_BUTTON = /<button[^>]*class="[^"]*\bbtn-(check|sign)\b[^"]*"[^>]*>/g

const pages = [
  'pages/scan/finished-in.vue',
  'pages/scan/semi-in.vue',
  'pages/scan/fai.vue',
  'pages/scan/oqc.vue',
  'components/ProcessInspection.vue'
]

test('审核/签核按钮不得用 draftId 置灰 (没草稿时应能现建再签)', () => {
  for (const rel of pages) {
    const src = readFileSync(resolve(root, rel), 'utf8')
    const buttons = src.match(SIGN_BUTTON) || []
    for (const btn of buttons) {
      const disabled = (btn.match(/:disabled="([^"]*)"/) || [])[1] || ''
      assert.ok(
        !/draftId/.test(disabled),
        `${rel}: 审核/签核按钮仍被 draftId 卡住 -> ${disabled}\n  (${btn.trim()})`
      )
    }
  }
})

test('成品入库页与半成品入库页的审核按钮口径一致 (都以 orderId 为准)', () => {
  const read = rel => readFileSync(resolve(root, rel), 'utf8')
  const grab = rel => {
    const src = read(rel)
    const m = src.match(/<button[^>]*class="[^"]*btn-check[^"]*"[^>]*:disabled="([^"]*)"/)
    return m && m[1]
  }
  assert.equal(grab('pages/scan/finished-in.vue'), '!orderId || submitting')
  assert.equal(grab('pages/scan/semi-in.vue'), '!orderId || submitting')
})
