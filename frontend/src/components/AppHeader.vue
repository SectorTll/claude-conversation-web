<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { notifyEnabled, pushEnabled, togglePush, toggleNotify } from '@/lib/notify'
import { api } from '@/api/client'
import type { PendingAsk, WaitingItem } from '@/api/client'
import { openSessionPath } from '@/lib/deepLink'
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

// The global "what needs me" panel: every chat turn parked on a permission/question card,
// across all projects. The ⏳ chip only renders while the live snapshot reports waiting turns.
const waitingOpen = ref(false)
const waitingItems = ref<WaitingItem[]>([])
async function toggleWaiting() {
  waitingOpen.value = !waitingOpen.value
  if (waitingOpen.value) {
    try {
      waitingItems.value = await api.waiting()
    } catch {
      waitingItems.value = []
    }
  }
}
function cardSummary(c: PendingAsk | undefined): string {
  if (!c) {
    return ''
  }
  return c.type === 'permission'
    ? `Allow ${c.toolName}?`
    : (c.questions[0]?.question ?? 'Question')
}
async function openWaiting(w: WaitingItem) {
  waitingOpen.value = false
  await openSessionPath(`/p/${w.projectId}/s/${w.sessionId}`)
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
      <div v-if="live.waitingCount > 0" class="waitwrap">
        <button
          class="btn btn-ghost waitbtn"
          :title="`${live.waitingCount} turn(s) waiting on your decision`"
          @click="toggleWaiting"
        >
          ⏳ {{ live.waitingCount }}
        </button>
        <div v-if="waitingOpen" class="backdrop" @click="waitingOpen = false"></div>
        <div v-if="waitingOpen" class="waitpanel">
          <div v-if="waitingItems.length === 0" class="waitempty faint">
            Nothing pending — the list may have just resolved
          </div>
          <button
            v-for="w in waitingItems"
            :key="w.sessionId"
            type="button"
            class="waititem"
            @click="openWaiting(w)"
          >
            <span class="w-title">{{ w.title }}</span>
            <span class="w-sub faint">
              {{ cardSummary(w.cards[0]) }}{{ w.cards.length > 1 ? `  (+${w.cards.length - 1})` : '' }}
            </span>
          </button>
        </div>
      </div>
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
.waitwrap {
  position: relative;
}
.waitbtn {
  color: var(--status-waiting);
  border-color: var(--status-waiting);
}
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 29;
}
.waitpanel {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  width: 360px;
  max-height: 55vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  padding: 4px;
  background: var(--bg0);
  border: 1px solid var(--border);
  border-radius: 10px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.45);
  z-index: 30;
}
.waitempty {
  padding: 14px;
  font-size: 12px;
  text-align: center;
}
.waititem {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  padding: 8px 10px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--text);
  font-family: var(--ui);
  text-align: left;
  cursor: pointer;
}
.waititem:hover {
  background: var(--tool-bg);
}
.w-title {
  font-size: 12.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.w-sub {
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

/* Mobile: brand + actions on the first row (wrapping as needed), search drops to its own row. */
@media (max-width: 880px) {
  .header {
    flex-wrap: wrap;
    gap: 8px;
    padding: 8px 10px;
  }
  .brand {
    min-width: 0;
  }
  .subtitle {
    display: none;
  }
  .search {
    order: 3;
    flex-basis: 100%;
    max-width: none;
    margin: 0;
  }
  .actions {
    min-width: 0;
    margin-left: auto;
    flex-wrap: wrap;
  }
  .waitpanel {
    width: min(360px, calc(100vw - 24px));
  }
}
</style>
