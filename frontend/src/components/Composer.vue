<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { ChatAttachment } from '@/api/client'
import { useConversationStore } from '@/stores/conversation'
import { useSessionsStore } from '@/stores/sessions'
import { useLiveStore } from '@/stores/live'
import { useUiStore } from '@/stores/ui'

const conv = useConversationStore()
const sessions = useSessionsStore()
const live = useLiveStore()
const ui = useUiStore()

// The open session may be running server-side without a local turn (page reloaded, or the turn was
// started in another tab). Surface that so Stop is reachable even when `conv.busy` is false.
const liveWorking = computed(() => {
  const id = sessions.selectedId
  return !sessions.draftMode && !!id && live.bySession[id] === 'WORKING'
})
const canStop = computed(() => conv.busy || liveWorking.value)

const ta = ref<HTMLTextAreaElement | null>(null)

// Permission mode + model for the current chat target (null in draft mode → "last used"). The
// pickers are pre-filled from per-session memory and switching sessions re-initialises them.
const sid = computed(() => (sessions.draftMode ? null : sessions.selectedId))
const mode = ref(sessions.permissionModeFor(sid.value))
const model = ref(sessions.modelFor(sid.value))

// Composer text, backed by the per-session draft memory: whatever is on screen IS the draft, so
// switching sessions (or reloading the page) never loses typed-but-unsent text.
const text = ref(sessions.draftTextFor(sid.value))
watch(text, (t) => sessions.setDraftText(sid.value, t))

const MODELS = [
  { value: '', label: 'model · default' },
  { value: 'haiku', label: 'haiku · fast' },
  { value: 'sonnet', label: 'sonnet · balanced' },
  { value: 'opus', label: 'opus · best' },
  { value: 'fable', label: 'fable · latest' },
]

// The interactive `default` mode (every tool asks via a browser card) only works on the sdk
// engine — the cli engine is headless and would silently deny everything. Gate it on capabilities.
// Pasted images ride the sdk protocol too, so the attach UI is gated the same way.
const sdkEngine = ref(false)
const imagesEnabled = ref(false)
const imageMaxCount = ref(4)
const imageMaxBytes = ref(5 * 1024 * 1024)
onMounted(async () => {
  autoGrow() // a restored draft may be multi-line
  try {
    const caps = await api.chatCapabilities()
    sdkEngine.value = caps.engine === 'sdk'
    imagesEnabled.value = caps.images === true
    if (caps.imageMaxCount) imageMaxCount.value = caps.imageMaxCount
    if (caps.imageMaxBytes) imageMaxBytes.value = caps.imageMaxBytes
  } catch {
    // capabilities unavailable (old server) — keep the headless mode list
  }
})

// Pasted/dropped images waiting to go with the next send.
interface PendingImage extends ChatAttachment {
  dataUrl: string
}
const images = ref<PendingImage[]>([])

function addImageFile(file: File) {
  if (!imagesEnabled.value || !file.type.startsWith('image/')) {
    return
  }
  const allowed = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']
  if (!allowed.includes(file.type)) {
    ui.setStatus(`Unsupported image type ${file.type} — use png/jpeg/gif/webp`)
    return
  }
  if (images.value.length >= imageMaxCount.value) {
    ui.setStatus(`At most ${imageMaxCount.value} images per message`)
    return
  }
  if (file.size > imageMaxBytes.value) {
    ui.setStatus(`Image too large (max ${Math.round(imageMaxBytes.value / 1024 / 1024)} MB)`)
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const dataUrl = String(reader.result ?? '')
    const comma = dataUrl.indexOf(',')
    if (comma < 0) {
      return
    }
    images.value.push({ mediaType: file.type, data: dataUrl.slice(comma + 1), dataUrl })
  }
  reader.readAsDataURL(file)
}

function onPaste(e: ClipboardEvent) {
  if (!imagesEnabled.value) {
    return
  }
  const items = e.clipboardData?.items
  if (!items) {
    return
  }
  for (const item of items) {
    if (item.kind === 'file' && item.type.startsWith('image/')) {
      const f = item.getAsFile()
      if (f) {
        e.preventDefault()
        addImageFile(f)
      }
    }
  }
}

function onDrop(e: DragEvent) {
  if (!imagesEnabled.value) {
    return
  }
  e.preventDefault()
  for (const f of e.dataTransfer?.files ?? []) {
    addImageFile(f)
  }
}

function removeImage(i: number) {
  images.value.splice(i, 1)
}

const MODES = computed(() => [
  ...(sdkEngine.value ? [{ value: 'default', label: 'default · ask each tool' }] : []),
  { value: 'plan', label: 'plan · read-only' },
  { value: 'acceptEdits', label: 'acceptEdits · edits files' },
  { value: 'bypassPermissions', label: 'bypass · full access' },
])

watch(sid, (s) => {
  mode.value = sessions.permissionModeFor(s)
  model.value = sessions.modelFor(s)
  exitHistory()
  text.value = sessions.draftTextFor(s)
  void nextTick(autoGrow)
})

function onModeChange() {
  sessions.setPermissionMode(sid.value, mode.value)
}

function onModelChange() {
  sessions.setModel(sid.value, model.value)
}

function autoGrow() {
  const el = ta.value
  if (!el) {
    return
  }
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 200)}px`
}

// ↑-history recall: an ↑ in an EMPTY composer cycles this conversation's own prompts (↓ goes back
// towards — and past — the newest, restoring the empty box). The list is captured when browsing
// starts so a mid-browse live reload can't shift the indices; index 0 = newest, -1 = not browsing.
// Real typing exits browsing via the DOM input event — programmatic recalls don't fire it.
const histList = ref<string[]>([])
const histIndex = ref(-1)

function exitHistory() {
  histIndex.value = -1
}

/** Set recalled text programmatically: grow the box and park the caret at the end. */
function setRecalled(t: string) {
  text.value = t
  void nextTick(() => {
    autoGrow()
    const el = ta.value
    el?.setSelectionRange(el.value.length, el.value.length)
  })
}

/** Step through the recall history; returns true when the key was consumed. */
function recallHistory(older: boolean): boolean {
  if (histIndex.value < 0) {
    if (!older || text.value !== '') {
      return false // only an ↑ in an empty composer starts browsing
    }
    histList.value = conv.userPromptHistory()
  }
  const next = histIndex.value + (older ? 1 : -1)
  if (next < 0) {
    // stepped past the newest prompt — back to the empty composer
    exitHistory()
    setRecalled('')
    return true
  }
  if (next >= histList.value.length) {
    return histIndex.value >= 0 // already at the oldest — swallow the key while browsing
  }
  histIndex.value = next
  setRecalled(histList.value[histList.value.length - 1 - next])
  return true
}

async function send() {
  const t = text.value.trim()
  const atts = images.value.map(({ mediaType, data }) => ({ mediaType, data }))
  if (!t && atts.length === 0) {
    return
  }
  exitHistory()
  text.value = ''
  images.value = []
  await nextTick()
  autoGrow()
  ta.value?.focus()
  // conv.send() queues this if a turn is already running, else sends it immediately.
  await conv.send(t, mode.value, model.value, atts.length > 0 ? atts : undefined)
}

function onInput() {
  exitHistory()
  autoGrow()
}

function onKeydown(e: KeyboardEvent) {
  if (e.isComposing) {
    return // IME composition owns Enter and the arrows
  }
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  } else if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
    if (recallHistory(e.key === 'ArrowUp')) {
      e.preventDefault()
    }
  } else if (e.key === 'Escape' && histIndex.value >= 0) {
    exitHistory()
    setRecalled('')
  }
}
</script>

<template>
  <div class="composer" @dragover.prevent @dragenter.prevent @drop="onDrop">
    <div v-if="images.length" class="attachments">
      <div v-for="(img, i) in images" :key="i" class="att-chip">
        <img :src="img.dataUrl" alt="" />
        <button class="att-x" type="button" title="Remove image" @click="removeImage(i)">✕</button>
      </div>
    </div>
    <div class="row">
      <textarea
        ref="ta"
        v-model="text"
        class="input ta"
        rows="1"
        :placeholder="
          conv.busy
            ? 'Claude is responding… (Enter to queue, Shift+Enter for newline)'
            : imagesEnabled
              ? 'Message Claude…  (Enter to send, paste/drop images, ↑ history)'
              : 'Message Claude…  (Enter to send, Shift+Enter for newline, ↑ history)'
        "
        @input="onInput"
        @keydown="onKeydown"
        @paste="onPaste"
      ></textarea>
      <button v-if="canStop" class="btn btn-ghost stop" @click="conv.cancel()">■ Stop</button>
      <button class="btn btn-accent" :disabled="!text.trim() && images.length === 0" @click="send">
        {{ conv.busy ? 'Queue ⏎' : 'Send ➤' }}
      </button>
    </div>
    <div class="meta">
      <span class="spinner" v-if="conv.busy"></span>
      <span class="faint label">Permissions</span>
      <select v-model="mode" class="mode" @change="onModeChange">
        <option v-for="m in MODES" :key="m.value" :value="m.value">{{ m.label }}</option>
      </select>
      <span class="faint label">Model</span>
      <select v-model="model" class="mode" @change="onModelChange">
        <option v-for="m in MODELS" :key="m.value" :value="m.value">{{ m.label }}</option>
      </select>
    </div>
  </div>
</template>

<style scoped>
.composer {
  flex: none;
  border-top: 1px solid var(--border);
  padding: 12px 18px 14px;
  background: var(--bg0);
}
.row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}
.attachments {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.att-chip {
  position: relative;
  border: 1px solid var(--border);
  border-radius: 7px;
  overflow: hidden;
  background: var(--tool-bg);
}
.att-chip img {
  display: block;
  height: 56px;
  max-width: 120px;
  object-fit: cover;
}
.att-x {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: var(--text);
  font-size: 10px;
  line-height: 1;
  cursor: pointer;
}
.att-x:hover {
  background: var(--accent);
  color: #1a1714;
}
.ta {
  resize: none;
  line-height: 1.5;
  max-height: 200px;
  overflow-y: auto;
  padding: 9px 12px;
}
.btn.stop {
  color: var(--status-waiting);
  border-color: var(--border);
}
.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 11.5px;
}
.label {
  letter-spacing: 0.03em;
}
.mode {
  background: var(--bg0);
  border: 1px solid var(--border);
  color: var(--text-dim);
  border-radius: 7px;
  padding: 3px 7px;
  font-family: var(--ui);
  font-size: 11.5px;
  outline: none;
}
.mode:focus {
  border-color: var(--accent);
}
.mode:disabled {
  opacity: 0.5;
}
</style>
