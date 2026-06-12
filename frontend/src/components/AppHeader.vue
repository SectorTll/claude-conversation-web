<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { notifyEnabled, pushEnabled, togglePush, toggleNotify } from '@/lib/notify'
import { useProjectsStore } from '@/stores/projects'
import { useSearchStore } from '@/stores/search'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useLiveStore } from '@/stores/live'

const search = useSearchStore()
const projects = useProjectsStore()
const ui = useUiStore()
const auth = useAuthStore()
const live = useLiveStore()

const DEBOUNCE_MS = 300
const query = ref('')
let timer: ReturnType<typeof setTimeout> | null = null

function cancelTimer() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

// Search as you type. Dropping below 2 chars exits search mode and restores the session list.
watch(query, (q) => {
  cancelTimer()
  if (q.trim().length < 2) {
    if (search.active) {
      search.clear()
    }
    return
  }
  timer = setTimeout(() => search.run(q), DEBOUNCE_MS)
})

onBeforeUnmount(cancelTimer)

async function logout() {
  live.disconnect()
  await auth.logout()
}

function submit() {
  // Enter runs immediately, bypassing the debounce.
  cancelTimer()
  if (query.value.trim().length >= 2) {
    search.run(query.value)
  }
}
function clear() {
  cancelTimer()
  query.value = ''
  search.clear()
}
function refresh() {
  projects.load()
  ui.setStatus('Refreshed')
}

// Browser notifications for background-tab chat events (permission cards, finished turns).
const notify = ref(notifyEnabled())
async function onToggleNotify() {
  notify.value = await toggleNotify()
  ui.setStatus(notify.value ? 'Notifications on' : 'Notifications off')
}

// Server push (web-push): pings even with the tab closed when a turn waits on a card.
const push = ref(pushEnabled())
async function onTogglePush() {
  const state = await togglePush()
  push.value = state === 'on'
  if (state === 'unavailable') {
    // Untrusted origin (self-signed cert not imported) — open the one-click trust helper.
    ui.certHelpOpen = true
    ui.setStatus('Push unavailable — trust the certificate first (see the dialog)')
    return
  }
  ui.setStatus(
    state === 'on' ? 'Push notifications on (works with the tab closed)' : 'Push notifications off',
  )
}
</script>

<template>
  <header class="header">
    <div class="brand">
      <div class="logo">✱</div>
      <div>
        <div class="title">Claude Conversations</div>
        <div class="subtitle faint">browse · search · resume · schedule</div>
      </div>
    </div>

    <div class="search">
      <input
        v-model="query"
        class="input"
        type="text"
        placeholder="Search all conversations…  (Enter)"
        @keydown.enter="submit"
        @keydown.esc="clear"
      />
      <span v-if="search.loading" class="spinner search-spinner"></span>
      <button
        v-else-if="search.active"
        class="clearx"
        title="Clear search"
        @click="clear"
      >
        ✕
      </button>
    </div>

    <div class="actions">
      <button class="btn btn-ghost" title="Windows Task Scheduler" @click="ui.scheduleOpen = true">
        ⏱ Schedule
      </button>
      <button
        class="btn btn-ghost"
        title="In-process scheduler (any OS)"
        @click="ui.serverScheduleOpen = true"
      >
        ⏱ Server
      </button>
      <button
        class="btn btn-ghost"
        :title="notify ? 'Notifications on (permission cards, finished turns)' : 'Notifications off'"
        @click="onToggleNotify"
      >
        {{ notify ? '🔔' : '🔕' }}
      </button>
      <button
        class="btn btn-ghost"
        :class="{ pushed: push }"
        :title="push ? 'Push on — pings even with the tab closed' : 'Push off (needs a trusted certificate)'"
        @click="onTogglePush"
      >
        📲
      </button>
      <button
        class="btn btn-ghost"
        title="Telegram notifications (no certificate needed)"
        @click="ui.telegramOpen = true"
      >
        ✈
      </button>
      <button class="btn btn-ghost" @click="refresh">⟳ Refresh</button>
      <button class="btn btn-ghost" title="Log out" @click="logout">⏻ Log out</button>
    </div>
  </header>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 10px 16px;
  background: var(--bg1);
  border-bottom: 1px solid var(--border);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 230px;
}
.logo {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--accent);
  color: #1a1410;
  display: grid;
  place-items: center;
  font-size: 17px;
  font-weight: 700;
}
.title {
  font-weight: 600;
  font-size: 14px;
}
.subtitle {
  font-size: 11px;
}
.search {
  flex: 1;
  position: relative;
  max-width: 620px;
  margin: 0 auto;
}
.search-spinner {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
}
.clearx {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  color: var(--text-faint);
  cursor: pointer;
  font-size: 13px;
}
.clearx:hover {
  color: var(--text);
}
.actions {
  display: flex;
  gap: 8px;
  min-width: 230px;
  justify-content: flex-end;
}
</style>
