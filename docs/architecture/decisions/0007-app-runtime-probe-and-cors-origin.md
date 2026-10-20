# 0007. App 运行时探测单一权威源 + CORS 覆盖 Capacitor 无端口 origin

- **状态**: 采纳 (持续维护)
- **首次落地**: v1.1.105
- **关联**: R19 / `app/src/utils/runtime.js` / `capacitor.config.json` / [ADR-0005](0005-canary-first-deploy.md)
- **症状**: APK 装机后完全无法登录 (2026-10-20)

## 背景

v1.1.80~v1.1.104 期间，"当前是不是 H5 浏览器"这个判断被**复制到 5 个文件**，每处都是同一句内联代码：

```js
typeof window !== 'undefined' && typeof document !== 'undefined' && typeof plus === 'undefined'
```

这句只认 **HBuilder 原生运行时**注入的全局对象 `plus`。但本项目的 APK 走的是 **Capacitor 6** (`@capacitor/android` 6.2.1)，Capacitor WebView 里**根本没有 `plus`** —— 于是 5 处**全部把 APK 误判成 H5**。

后果链 (logcat 直接证据)：

```
[api] DEBUG base: /api                                        ← getBase() 走了 H5 分支
Handling local request: http://localhost/api/system/config/...  ← 打回 WebView 自己的服务器
[api] 非 JSON 响应: status=200 ct=text/html data=<!DOCTYPE html>
```

Capacitor 内置的本地 HTTP server 找不到该文件，SPA fallback 返回**打包进 APK 的 `index.html`**。登录接口同理拿不到 JSON → 全端无法登录。

同时 `utils/nav.js` 的 `isNative()` 用的是 `typeof plus !== 'undefined'`，同样误判 → 原生 App 里所有跳转退化成 `window.location.hash`，`uni.switchTab` / `uni.reLaunch` 全部失效。

**第二个坑 (修完第一个立刻撞上)**: `capacitor.config.json` 设了 `androidScheme: "http"` 且无端口，APK 的真实 origin 是**无端口的 `http://localhost`**。而后端两道 CORS 闸门都只配了带端口的通配：

- `SecurityConfig.corsConfigurationSource` — `addAllowedOriginPattern("http://localhost:*")`
- `SaTokenConfig.corsFilter` (注册在 `/api/**`) — `addAllowedOriginPattern("http://localhost:*")`

实测矩阵 (修前直连后端)：

| Origin | GET | OPTIONS |
|---|---|---|
| `http://localhost` (APK 实际) | **403** | **403** |
| `http://localhost:5173` | 401 ✅ | 200 ✅ |

## 决定

### 1. 运行时探测收敛到单一权威源 `app/src/utils/runtime.js`

判定顺序 (先认 Capacitor，再退回 HBuilder 的 `plus`，最后才是浏览器)：

```js
export function isNativeApp() {
  if (typeof window === 'undefined') return false
  const cap = window.Capacitor
  if (cap) {
    if (typeof cap.isNativePlatform === 'function') return !!cap.isNativePlatform()
    if (typeof cap.getPlatform === 'function') return cap.getPlatform() !== 'web'
    return true   // 有全局但方法不可用 → 保守当原生
  }
  return typeof plus !== 'undefined'   // HBuilder 原生运行时 (老路径)
}
export function isH5Runtime() { /* window+document 存在 && !isNativeApp() */ }
```

6 处调用点全部改为 import，不再各自内联：
`api/index.js` · `utils/nav.js` · `utils/upload.js` · `pages/login/index.vue` · `pages/profile/settings.vue`

### 2. 后端 CORS 两处都补无端口形态

`SecurityConfig` + `SaTokenConfig` 各加 `http://localhost` / `http://127.0.0.1` (不带 `:*`)。

### 3. 原生 base 也读 storage 显式覆盖

新增 `getNativeBase()`：用户在「服务器设置」填了绝对地址时原生侧同样生效 (此前只有 H5 分支读)。

## 备选

- **只改 `getBase()` 一处**：能修登录，但 `nav.js` / `upload.js` 仍误判，跳转和上传仍是坏的。
- **改用 `uni.getSystemInfoSync().uniPlatform`**：uni-app 官方推荐，但 Capacitor 打包下该字段的取值语义与 HBuilder 原生不同，且旧版 uni 运行时不一定填充，风险高于直接读 Capacitor 全局。
- **APK 里 `server.url` 指向线上 H5 而非打包本地资源**：可绕开 base 判定，但放弃离线能力，且与现有 H5 同源设计冲突。
- **后端 CORS 直接放行 `*`**：Spring Boot 3 (Framework 6) 的 `setAllowedOrigins` 不支持 `*`；且 `allowCredentials(true)` 下规范要求精确 origin，不采用。

## 后果

- **新增文件即改即生效的唯一真相点**：新增运行时代码一律 import `utils/runtime.js`，不再写 `typeof plus` 内联判断。
- **App H5 (:18090) 行为不变**：在浏览器里打开时 `window.Capacitor` 不存在，走浏览器分支判 H5，与既有同源 `/api` 设计一致。
- **巡检方法固化**：APK 类问题优先看 logcat 的 `[api] DEBUG base:` 一行 —— 它直接告诉你 base 解析成了什么，比读代码快。
- **CORS 类 403 一律实测矩阵**：不要靠推理哪个 pattern 匹配得到。`curl -H "Origin: <各候选>"` + 同时看 GET 和 OPTIONS (预检 200 但实际 403 意味着被另一道闸门拦了)。
- 本次 home 部署沿用 [ADR-0006](0006-baked-jar-dockercp-two-track.md) 的 baked-in 三连；jar md5 `2246eb53...` 在本地 / 飞牛容器 / home 镜像内三方一致。`SA_TOKEN_JWT_SECRET_KEY` 保持原值，已登录用户未被强制登出。