// 需求: 工作台首屏数据慢 — 原来 11 个请求逐个 await 串行 (4G 每次往返 200~600ms 被放大成几秒)。
// 编排抽到 utils/dashboardLoad.js 注入依赖单测 (对齐 ocrCapture.test.mjs):
//   ① 启动轮: 模块配置 / 区块配置 / /me 三者并行, 互不阻塞, 单个失败不拖垮其它;
//   ② 待办块: 6 类查询并行发起, 合并顺序固定 (与页面展示顺序一致), 单块失败/停用不影响其它.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { loadBootstraps, runTodoBlocks } from '../src/utils/dashboardLoad.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const tick = () => new Promise(r => setTimeout(r, 0))

test('runTodoBlocks 在所有块返回前就并行发起全部启用块', async () => {
  const started = []
  const gates = [deferred(), deferred(), deferred()]
  const blocks = gates.map((g, i) => ({
    key: 'b' + i,
    enabled: true,
    run: async () => { started.push(i); await g.promise; return [i] }
  }))

  const p = runTodoBlocks(blocks)
  await tick()
  assert.deepEqual(started, [0, 1, 2], '三个块应全部已发起, 而不是等前一个返回')

  gates[2].resolve(); gates[0].resolve(); gates[1].resolve()
  assert.deepEqual(await p, [0, 1, 2])
})

test('runTodoBlocks 合并顺序固定, 不随返回先后变化', async () => {
  const g = [deferred(), deferred(), deferred()]
  const blocks = g.map((d, i) => ({
    key: 'b' + i,
    enabled: true,
    run: async () => { await d.promise; return ['item' + i] }
  }))

  const p = runTodoBlocks(blocks)
  g[2].resolve(); g[1].resolve(); g[0].resolve()
  assert.deepEqual(await p, ['item0', 'item1', 'item2'])
})

test('runTodoBlocks 停用的块不发请求也不占位', async () => {
  const called = []
  const out = await runTodoBlocks([
    { key: 'a', enabled: true, run: async () => { called.push('a'); return ['a'] } },
    { key: 'b', enabled: false, run: async () => { called.push('b'); return ['b'] } },
    { key: 'c', enabled: true, run: async () => { called.push('c'); return ['c'] } }
  ])
  assert.deepEqual(called, ['a', 'c'])
  assert.deepEqual(out, ['a', 'c'])
})

test('runTodoBlocks 单块抛错不影响其它块', async () => {
  const out = await runTodoBlocks([
    { key: 'a', enabled: true, run: async () => { throw new Error('403') } },
    { key: 'b', enabled: true, run: async () => ['b'] }
  ])
  assert.deepEqual(out, ['b'])
})

test('runTodoBlocks 同步抛错也不拖垮其它块', async () => {
  const out = await runTodoBlocks([
    { key: 'a', enabled: true, run: () => { throw new Error('boom') } },
    { key: 'b', enabled: true, run: async () => ['b'] }
  ])
  assert.deepEqual(out, ['b'])
})

test('loadBootstraps 三项并行发起, 全部返回前不串行等待', async () => {
  const started = []
  const m = deferred(); const d = deferred(); const me = deferred()
  const p = loadBootstraps({
    loadModuleConfig: async () => { started.push('module'); await m.promise; return true },
    loadDashboardConfig: async () => { started.push('dash'); await d.promise; return true },
    me: async () => { started.push('me'); await me.promise; return { id: 7 } }
  })

  await tick()
  assert.deepEqual(started, ['module', 'dash', 'me'], '三项应同时发起')

  me.resolve(); m.resolve(); d.resolve()
  const r = await p
  assert.deepEqual(r.me, { id: 7 })
  assert.equal(r.moduleConfigOk, true)
  assert.equal(r.dashboardConfigOk, true)
})

test('loadBootstraps 单个失败不影响其它, me 失败返回 null', async () => {
  const r = await loadBootstraps({
    loadModuleConfig: async () => { throw new Error('net') },
    loadDashboardConfig: async () => true,
    me: async () => { throw new Error('401') }
  })
  assert.equal(r.moduleConfigOk, false)
  assert.equal(r.dashboardConfigOk, true)
  assert.equal(r.me, null)
})
