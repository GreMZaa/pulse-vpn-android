// ПУЛЬС Связь Service Worker
const CACHE_NAME = 'pulse-call-v3';
const ASSETS = [
  './',
  './index.html',
  './mqtt.min.js',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './apple-touch-icon.png',
  './favicon.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(ASSETS).catch(() => {});
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  // Пропускаем внешние запросы к WebRTC, MQTT брокерам и стримингу напрямую в сеть
  const url = event.request.url;
  if (
    url.includes('vdo.ninja') ||
    url.includes('emqx.io') ||
    url.includes('hivemq.com') ||
    url.includes('wss://') ||
    url.includes('ws://') ||
    event.request.method !== 'GET'
  ) {
    return;
  }
  event.respondWith(
    fetch(event.request).catch(() => {
      return caches.match(event.request);
    })
  );
});

// КЛИК ПО УВЕДОМЛЕНИЮ О ЗВОНКЕ (РАЗВОРАЧИВАЕТ ОКНО НА РАБОЧЕМ СТОЛЕ И ВХОДИТ В ВЫЗОВ)
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const roomCode = (event.notification.data && event.notification.data.roomCode) || '';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if ('focus' in client) {
          client.postMessage({ action: 'answer_call', roomCode: roomCode });
          return client.focus();
        }
      }
      if (self.clients.openWindow) {
        const dest = roomCode ? './?room=' + encodeURIComponent(roomCode) + '&auto=1' : './';
        return self.clients.openWindow(dest);
      }
    })
  );
});
