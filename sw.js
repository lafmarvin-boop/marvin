const CACHE = 'parlons-v3';
const PRECACHE = ['/agent-app.html', '/', '/index.html', '/manifest-visitor.json'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(PRECACHE)));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  if (e.request.url.includes('/.netlify/') || e.request.url.includes('/api/')) return;
  if (e.request.method !== 'GET') return;
  e.respondWith(
    fetch(e.request)
      .then(res => {
        if (res && res.status === 200) {
          const clone = res.clone();
          caches.open(CACHE).then(c => c.put(e.request, clone));
        }
        return res;
      })
      .catch(() => caches.match(e.request, { ignoreSearch: true }))
  );
});

self.addEventListener('push', e => {
  let data = { title: '💬 Nouveau tchat', body: 'Un visiteur attend votre aide', url: '/agent-app.html' };
  try { data = { ...data, ...e.data.json() }; } catch {}

  e.waitUntil(
    self.registration.showNotification(data.title, {
      body: data.body,
      icon: 'data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 192 192\'%3E%3Crect width=\'192\' height=\'192\' rx=\'40\' fill=\'%23C4714A\'/%3E%3Crect x=\'28\' y=\'26\' width=\'136\' height=\'106\' rx=\'22\' fill=\'white\'/%3E%3Cpolygon points=\'48,132 48,162 76,132\' fill=\'white\'/%3E%3Ctext x=\'96\' y=\'104\' font-size=\'70\' text-anchor=\'middle\' font-family=\'Georgia,serif\' fill=\'%23C4714A\'%3EP%3C/text%3E%3C/svg%3E',
      badge: 'data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 96 96\'%3E%3Crect width=\'96\' height=\'96\' rx=\'20\' fill=\'%23C4714A\'/%3E%3Crect x=\'14\' y=\'12\' width=\'68\' height=\'53\' rx=\'11\' fill=\'white\'/%3E%3Cpolygon points=\'24,65 24,83 38,65\' fill=\'white\'/%3E%3Ctext x=\'48\' y=\'51\' font-size=\'34\' text-anchor=\'middle\' font-family=\'Georgia,serif\' fill=\'%23C4714A\'%3EP%3C/text%3E%3C/svg%3E',
      data: { url: data.url },
      vibrate: [200, 100, 200, 100, 200],
      requireInteraction: true,
      tag: 'parlons-chat',
      renotify: true
    })
  );
});

self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = e.notification.data?.url || '/agent-app.html';
  e.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
      for (const c of list) {
        if (c.url.includes(url) && 'focus' in c) return c.focus();
      }
      return clients.openWindow(url);
    })
  );
});
