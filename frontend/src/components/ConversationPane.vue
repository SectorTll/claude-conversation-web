<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useSessionsStore } from '@/stores/sessions'
import { useConversationStore } from '@/stores/conversation'
import { useProjectsStore } from '@/stores/projects'
import { useActionsStore } from '@/stores/actions'
import { useUiStore } from '@/stores/ui'
import MessageCard from './MessageCard.vue'
import Composer from './Composer.vue'

const sessions = useSessionsStore()
const conv = useConversationStore()
const projects = useProjectsStore()
const actions = useActionsStore()
const ui = useUiStore()

const session = computed(() => sessions.selected)
const pid = computed(() => projects.selectedId ?? sessions.projectId ?? '')

const subtitle = computed(() => {
  const s = session.value
  if (!s) {
    return ''
  }
  const parts = [s.shortId, `${s.totalMessages} msgs`]
  if (s.version) {
    parts.push(`v${s.version}`)
  }
  if (s.usageLine) {
    parts.push(s.usageLine)
  }
  return parts.join('  ·  ')
})

const subtitleTooltip = computed(() => session.value?.usageTooltip ?? '')

const draftSubtitle = computed(() => {
  const p = projects.selected
  return p ? `in ${p.shortName}` : ''
})

function openRename() {
  if (session.value) {
    ui.openRename({ projectId: pid.value, session: session.value })
  }
}

// Auto-scroll: follow new content while the user is parked near the bottom; don't yank if they
// scrolled up to read history. Streaming mutates the last bubble's text in place, so we watch that.
const messagesEl = ref<HTMLElement | null>(null)
let stick = true

function onScroll() {
  const el = messagesEl.value
  if (el) {
    stick = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }
}
function scrollToBottom() {
  const el = messagesEl.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}

watch(
  () => [conv.messages.length, conv.messages.at(-1)?.text, conv.queue.length] as const,
  () => {
    if (stick) {
      nextTick(scrollToBottom)
    }
  },
  { flush: 'post' },
)

// On opening a session (or starting a draft), jump to the bottom and re-stick.
watch(
  () => [sessions.selectedId, sessions.draftMode] as const,
  () => {
    stick = true
    nextTick(scrollToBottom)
  },
)
</script>

<template>
  <div class="pane">
    <template v-if="session || sessions.draftMode">
      <div v-if="session" class="toolbar">
        <div class="titles">
          <div class="title">{{ session.title }}</div>
          <div class="subtitle faint" :title="subtitleTooltip">{{ subtitle }}</div>
        </div>
        <div class="buttons">
          <button class="btn btn-accent" @click="sessions.startDraft()">＋ New chat</button>
          <button class="btn btn-ghost" @click="openRename">✎ Rename</button>
          <button class="btn btn-ghost" @click="actions.resume(pid, session.sessionId, false)">
            ▶ Resume in CLI
          </button>
          <button class="btn btn-ghost" @click="actions.resume(pid, session.sessionId, true)">
            ⑂ Fork
          </button>
          <button class="btn btn-ghost" @click="actions.newSession(pid)">＋ New in CLI</button>
        </div>
      </div>
      <div v-else class="toolbar">
        <div class="titles">
          <div class="title">New chat</div>
          <div class="subtitle faint">{{ draftSubtitle }}</div>
        </div>
      </div>

      <div v-if="session" class="convsearch">
        <input v-model="conv.search" class="input" placeholder="Search in this conversation…" />
      </div>

      <div ref="messagesEl" class="messages" @scroll="onScroll">
        <div v-if="conv.loading" class="empty faint">Loading…</div>
        <MessageCard v-for="(m, i) in conv.filtered" :key="i" :msg="m" />
        <div v-for="(q, i) in conv.queue" :key="'q' + i" class="queued">
          <span class="qtag">⧗ queued</span>
          <span class="qtext">{{ q.text }}</span>
          <button class="qx" title="Remove from queue" @click="conv.removeQueued(i)">✕</button>
        </div>
        <div v-if="!conv.loading && conv.filtered.length === 0 && conv.queue.length === 0" class="empty faint">
          {{
            session
              ? conv.search
                ? 'No messages match your filter'
                : 'Empty conversation'
              : 'Send a message below to start a new chat'
          }}
        </div>
      </div>

      <Composer />
    </template>

    <div v-else class="placeholder">
      <div class="bubble">💬</div>
      <div class="faint">Select a session, or start a new chat</div>
    </div>
  </div>
</template>

<style scoped>
.pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg0);
  min-width: 0;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--border);
}
.titles {
  min-width: 0;
}
.title {
  font-weight: 600;
  font-size: 15px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.subtitle {
  font-size: 11.5px;
  margin-top: 2px;
}
.buttons {
  display: flex;
  gap: 8px;
  flex: none;
}
.convsearch {
  padding: 10px 18px 0;
}
.messages {
  flex: 1;
  overflow: auto;
  padding: 16px 18px 28px;
}
.placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.bubble {
  font-size: 42px;
  opacity: 0.5;
}
.empty {
  padding: 30px 10px;
  text-align: center;
}
.queued {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 0;
  padding: 8px 12px;
  border: 1px dashed var(--border);
  border-radius: 10px;
  opacity: 0.6;
  font-size: 13px;
}
.qtag {
  flex: none;
  color: var(--accent);
  font-size: 11px;
  letter-spacing: 0.03em;
}
.qtext {
  flex: 1;
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--text-dim);
}
.qx {
  flex: none;
  background: none;
  border: none;
  color: var(--text-dim);
  cursor: pointer;
  font-size: 12px;
  padding: 2px 4px;
  line-height: 1;
}
.qx:hover {
  color: var(--status-waiting);
}
</style>
