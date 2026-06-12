// Service worker: shows web-push notifications for WAITING chat turns and deep-links clicks back
// into the app. Kept dependency-free and tiny — it only ever runs push/notificationclick.

self.addEventListener('install', () => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('push', (event) => {
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    data = { title: 'Claude', body: event.data ? event.data.text() : '' }
  }
  const title = data.title || 'Claude is waiting'
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || '',
      tag: data.tag || 'claude-waiting',
      data: { path: data.path || '' },
    }),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const path = (event.notification.data && event.notification.data.path) || ''
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolledClients: true }).then(async (clients) => {
      for (const client of clients) {
        // An app tab is already open: focus it and let it navigate in-app.
        await client.focus()
        client.postMessage({ type: 'open-session', path })
        return
      }
      return self.clients.openWindow(self.registration.scope + '#' + path)
    }),
  )
})
