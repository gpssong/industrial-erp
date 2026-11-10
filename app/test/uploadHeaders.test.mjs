// 回归: App(Capacitor APK) 原料入库照片上传 "上传失败"
// 根因: CapacitorHttp 6.x 原生 CapacitorHttpUrlConnection.setRequestBody 在
//   Content-Type 为空时直接 return 不写 body; writeFormDataRequestBody 又从
//   Content-Type 里 split 出 boundary。JS 桥 convertBody(FormData) 不带 Content-Type,
//   于是原生发出一个无 body 的非 multipart 请求 → 后端 MultipartException 500。
// 修复: 原生容器显式带 `multipart/form-data; boundary=<token>`; 真浏览器(H5)不能带
//   (浏览器要按实际序列化自动生成 boundary, 手动设会不符)。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildUploadHeaders } from '../src/utils/uploadHeaders.js'

test('原生容器: 带 X-Client-Type 且 Content-Type 含可用 boundary', () => {
  const h = buildUploadHeaders(true)
  assert.equal(h['X-Client-Type'], 'APP')
  assert.match(h['Content-Type'], /^multipart\/form-data; boundary=[A-Za-z0-9-]+$/)
  const boundary = h['Content-Type'].slice(h['Content-Type'].indexOf('boundary=') + 9)
  assert.ok(boundary.length >= 8, 'boundary 足够长, 避免与文件内容碰撞')
  // 原生 writeFormDataRequestBody 用 split(";")[1].split("=")[1] 取 boundary,
  // 所以 token 里绝不能含 ; = 或空格, 否则截断/越界。
  assert.ok(!boundary.includes(';'))
  assert.ok(!boundary.includes('='))
  assert.ok(!boundary.includes(' '))
})

test('H5 浏览器: 不设 Content-Type, 交给浏览器自动生成 boundary', () => {
  const h = buildUploadHeaders(false)
  assert.equal(h['X-Client-Type'], 'APP')
  assert.equal(h['Content-Type'], undefined)
})

test('额外 header 被合并 (原生与 H5 都保留)', () => {
  assert.equal(buildUploadHeaders(true, { Foo: 'bar' }).Foo, 'bar')
  assert.equal(buildUploadHeaders(false, { Foo: 'bar' }).Foo, 'bar')
})

test('两次原生调用生成不同 boundary (动态, 降低与内容碰撞概率)', () => {
  const a = buildUploadHeaders(true)['Content-Type']
  const b = buildUploadHeaders(true)['Content-Type']
  assert.notEqual(a, b)
})