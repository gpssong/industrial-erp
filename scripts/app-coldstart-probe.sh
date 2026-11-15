#!/bin/bash
# ============================================================
# scripts/app-coldstart-probe.sh — Android App 冷启动首屏计时 (真机, ADB + CDP)
#
# 用途: 回答「App 关掉再打开要等多久才出数据」, 把体感变成可复现的数字.
#   背景: v1.1.191 之前只报「加载时间过长」, 无量级无归因. 本脚本量出:
#     静态框架(全部功能网格) ms  /  数据区块(库存预警·现场待办) ms
#   已知陷阱: App 的请求走 CapacitorHttp 原生层(Java), **不出现在 WebView 的
#   performance resource timing 里** —— 只看资源计时会误判成「一次请求都没发」.
#   真凶定位要看 logcat 的 CapacitorHttp/CapacitorCookies 时间线.
#
# 用法:
#   ./scripts/app-coldstart-probe.sh                # 默认包名 com.pengcheng.erp
#   PKG=com.xxx.erp ./scripts/app-coldstart-probe.sh
#
# 依赖: adb (设备已 USB 连上并授权), node >= 18 (用内置 WebSocket 直连 CDP)
# ============================================================
set -uo pipefail

PKG="${PKG:-com.pengcheng.erp}"
ACT="$PKG/.MainActivity"
PORT=9333
MARKERS='[["静态框架","全部功能"],["库存预警","库存预警"],["现场待办","现场待办"]]'

cd "$(dirname "$0")/.." || exit 1
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/poll.mjs" <<'EOF'
const PORT = process.env.CDP_PORT, T0 = Number(process.env.T0_MS);
const MARKERS = JSON.parse(process.env.MARKERS);
const DEADLINE = Number(process.env.DEADLINE_MS || 60000);
const seen = {};
const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = list.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) { console.log(JSON.stringify({ error: 'no page target' })); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0; const pending = new Map();
let lastText = '';
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); } };
const send = (method, params) => new Promise(res => { const myId = ++id; pending.set(myId, res); ws.send(JSON.stringify({ id: myId, method, params })); });
await new Promise(res => { ws.onopen = res; });
while (Date.now() - T0 < DEADLINE) {
  const m = await send('Runtime.evaluate', { expression: "document.body ? document.body.innerText : ''", returnByValue: true });
  const txt = (m.result && m.result.result && m.result.result.value) || '';
  lastText = txt;
  for (const [name, key] of MARKERS) {
    if (!seen[name] && txt.includes(key)) seen[name] = Date.now() - T0;
  }
  if (Object.keys(seen).length === MARKERS.length) break;
  await new Promise(r => setTimeout(r, 120));
}
const u = await send('Runtime.evaluate', { expression: 'location.href', returnByValue: true });
const url = (u.result && u.result.result && u.result.result.value) || '';
console.log(JSON.stringify({
  ...seen,
  url,
  // 停在登录页 = 会话没了, 计时无意义 (先在 App 里登录再测)
  hint: url.indexOf('/pages/login') >= 0 || lastText.indexOf('登 录') >= 0
    ? '⚠ 当前在登录页, 没有会话 —— 先登录再测'
    : (Object.keys(seen).length < MARKERS.length ? '⚠ 数据区块未出现(超时)' : ''),
}));
ws.close();
EOF

adb shell am force-stop "$PKG" || exit 1
sleep 2
adb forward --remove-all >/dev/null 2>&1
T0=$(python3 -c 'import time;print(int(time.time()*1000))')
adb shell am start -n "$ACT" >/dev/null || exit 1

PID=""; SOCK=""
for i in $(seq 1 120); do
  PID=$(adb shell ps -A | grep -i "$PKG" | awk '{print $2}' | tr -d '\r' | head -1)
  [ -n "$PID" ] && break
  sleep 0.2
done
for i in $(seq 1 60); do
  SOCK=$(adb shell cat /proc/net/unix | grep -o "webview_devtools_remote_$PID" | head -1 | tr -d '\r')
  [ -n "$SOCK" ] && break
  sleep 0.2
done
[ -z "$SOCK" ] && { echo "✗ 拿不到 WebView devtools socket (App 没起来?)"; exit 1; }

adb forward tcp:$PORT localabstract:$SOCK >/dev/null
sleep 0.3
echo "包: $PKG  pid: $PID"
CDP_PORT=$PORT T0_MS=$T0 MARKERS="$MARKERS" node "$WORK/poll.mjs"
