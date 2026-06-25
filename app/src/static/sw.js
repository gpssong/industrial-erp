// 鹏程ERP Service Worker
// 策略: 网络优先, 失败时用缓存 (Network First)
const CACHE_NAME = 'erp-pwa-v1';
const PRECACHE = [
  '/static/icons/icon-192.png',
  '/static/icons/icon-512.png',
  '/static/icons/apple-touch-icon.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(PRECACHE).catch(() => {}))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  // API 请求: 网络优先, 不缓存
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(request).catch(() => new Response(JSON.stringify({
        code: -1, msg: '网络不可用, 请检查连接'
      }), { status: 503, headers: { 'Content-Type': 'application/json' } }))
    );
    return;
  }

  // 静态资源/页面: 网络优先, 失败回退缓存
  event.respondWith(
    fetch(request)
      .then((response) => {
        // 成功: 缓存一份 (排除大文件)
        if (response.ok && response.status === 200) {
          const clone = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, clone)).catch(() => {});
        }
        return response;
      })
      .catch(() => caches.match(request).then((cached) => cached || new Response('Offline', { status: 503 })))
  );
});