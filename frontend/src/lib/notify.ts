// Browser notifications for chat events the user would otherwise miss in a background tab:
// a permission/question card appearing, or a long turn finishing. Gated on an explicit opt-in
// toggle (AppHeader bell) AND the tab being hidden/unfocused — a visible chat needs no toast.

const KEY = 'claude.chat.notify'

export function notifyEnabled(): boolean {
  try {
    return localStorage.getItem(KEY) === '1'
  } catch {
    return false
  }
}

/** Flip the toggle; turning it on asks the browser for permission. Returns the new state. */
export async function toggleNotify(): Promise<boolean> {
  if (notifyEnabled()) {
    localStorage.setItem(KEY, '0')
    return false
  }
  if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
    await Notification.requestPermission()
  }
  const granted = typeof Notification !== 'undefined' && Notification.permission === 'granted'
  localStorage.setItem(KEY, granted ? '1' : '0')
  return granted
}

// ---- web push (server-sent, works with the tab closed) -------------------------------------
// Requires a TRUSTED https origin (or localhost): on the LAN the self-signed cert must be imported
// on the client, otherwise SW registration fails and togglePush() reports 'unavailable' — the
// in-tab notifications above keep working either way.

const PUSH_KEY = 'claude.chat.push'

export type PushState = 'on' | 'off' | 'unavailable'

export function pushSupported(): boolean {
  return 'serviceWorker' in navigator && 'PushManager' in window
}

export function pushEnabled(): boolean {
  try {
    return localStorage.getItem(PUSH_KEY) === '1'
  } catch {
    return false
  }
}

/** Flip server push: register the SW + subscribe, or unsubscribe. Never throws. */
export async function togglePush(): Promise<PushState> {
  if (!pushSupported()) {
    return 'unavailable'
  }
  if (pushEnabled()) {
    try {
      const reg = await navigator.serviceWorker.getRegistration('/sw.js')
      const sub = await reg?.pushManager.getSubscription()
      if (sub) {
        await fetch('/api/push/unsubscribe', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ endpoint: sub.endpoint }),
        })
        await sub.unsubscribe()
      }
    } catch {
      // best-effort teardown
    }
    localStorage.setItem(PUSH_KEY, '0')
    return 'off'
  }
  try {
    if (Notification.permission === 'default') {
      await Notification.requestPermission()
    }
    if (Notification.permission !== 'granted') {
      return 'off'
    }
    const reg = await navigator.serviceWorker.register('/sw.js')
    const keyRes = await fetch('/api/push/key')
    if (!keyRes.ok) {
      return 'unavailable' // push disabled server-side
    }
    const { key } = (await keyRes.json()) as { key: string }
    const sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(key),
    })
    const json = sub.toJSON()
    const res = await fetch('/api/push/subscribe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ endpoint: sub.endpoint, keys: json.keys }),
    })
    if (!res.ok) {
      return 'unavailable'
    }
    localStorage.setItem(PUSH_KEY, '1')
    return 'on'
  } catch {
    // SW registration fails on untrusted origins (self-signed cert not imported) — degrade quietly.
    return 'unavailable'
  }
}

function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4)
  const raw = atob((base64 + padding).replace(/-/g, '+').replace(/_/g, '/'))
  const out = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i++) {
    out[i] = raw.charCodeAt(i)
  }
  return out
}

/** Fire a notification if enabled, permitted, and the tab is not in the user's face. */
export function maybeNotify(title: string, body: string) {
  if (!notifyEnabled() || typeof Notification === 'undefined' || Notification.permission !== 'granted') {
    return
  }
  if (document.visibilityState === 'visible' && document.hasFocus()) {
    return
  }
  try {
    new Notification(title, { body: body.slice(0, 160), tag: 'claude-web-chat' })
  } catch {
    // notification construction can throw on some platforms — never break the stream over a toast
  }
}
