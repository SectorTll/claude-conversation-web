<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { ChatAttachment } from '@/api/client'
import type { SlashCommandInfo } from '@/types/models'
import { atFragment, filterCommands, filterFiles, slashFragment } from '@/lib/slashCommands'
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
watch(text, (t) => {
  sessions.setDraftText(sid.value, t)
  if (t.startsWith('/')) {
    ensureCatalog() // a restored "/cmd args" draft needs the catalog for the argument hint
  }
})

// Edit & resend: the ✎ button on a user message seeds the composer with the original text; the
// armed state (banner + fork-on-send) lives in the conversation store.
watch(
  () => conv.editSeedText,
  (t) => {
    if (t != null) {
      text.value = t
      conv.clearEditSeed()
      void nextTick(() => {
        autoGrow()
        ta.value?.focus()
      })
    }
  },
)

// Slash-command autocomplete: typing "/name" as the whole message offers the user's skills and
// custom commands (user-level + this project's .claude). Picking one only edits the composer text —
// the CLI expands /name itself when the message is sent, so the send path stays untouched.
const slashAll = ref<SlashCommandInfo[]>([])
let slashLoadedFor: string | null | undefined // project the catalog was fetched for; undefined = never
const slashDismissed = ref(false)
const slashIndex = ref(0)
const slashFrag = computed(() => slashFragment(text.value))
const slashItems = computed(() =>
  slashFrag.value === null ? [] : filterCommands(slashAll.value, slashFrag.value),
)
const slashOpen = computed(() => !slashDismissed.value && slashItems.value.length > 0)

function ensureCatalog() {
  const pid = sessions.projectId
  if (slashLoadedFor !== pid) {
    slashLoadedFor = pid
    api.chatCommands(pid).then(
      (list) => (slashAll.value = list),
      () => (slashAll.value = []), // old server / transient error — type on without the popup
    )
  }
}

watch(slashFrag, (f) => {
  // Each fragment change re-opens a popup dismissed with Esc and resets the highlight.
  slashDismissed.value = false
  slashIndex.value = 0
  if (f !== null) {
    ensureCatalog()
  }
})

// Once a command is complete ("/name args…"), keep its argument-hint visible as a helper row.
const slashHint = computed(() => {
  if (slashOpen.value) {
    return null // the popup itself already shows the hint
  }
  const m = /^\/(\S+)\s/.exec(text.value)
  if (!m) {
    return null
  }
  const c = slashAll.value.find((x) => x.name === m[1])
  return c?.argumentHint ? { name: c.name, hint: c.argumentHint } : null
})

/** Put the picked command into the composer, ready for its arguments. */
function acceptSlash(c: SlashCommandInfo) {
  text.value = `/${c.name} ` // the trailing space ends the fragment, closing the popup
  void nextTick(() => {
    autoGrow()
    const el = ta.value
    el?.focus()
    el?.setSelectionRange(el.value.length, el.value.length)
  })
}

// @-file autocomplete: an "@token" at the CARET (anywhere in the message) offers files from the
// chat target's cwd — the CLI reads `@path` references itself. The fragment is caret-dependent,
// so it is recomputed on input/keyup/click rather than derived from the text alone.
const atAll = ref<string[]>([])
let atLoadedFor: string | null = null // `${pid}|${sid}` the listing was fetched for
const atFrag = ref<string | null>(null)
const atStart = ref(0)
const atDismissed = ref(false)
const atIndex = ref(0)
const atItems = computed(() =>
  atFrag.value === null ? [] : filterFiles(atAll.value, atFrag.value),
)
const atOpen = computed(() => !atDismissed.value && atItems.value.length > 0)

function updateAtFragment() {
  const el = ta.value
  const upto = el ? text.value.slice(0, el.selectionStart ?? text.value.length) : ''
  const m = el ? atFragment(upto) : null
  if (!m) {
    atFrag.value = null
    return
  }
  if (atFrag.value !== m.fragment) {
    atDismissed.value = false
    atIndex.value = 0
  }
  atFrag.value = m.fragment
  atStart.value = m.start
  ensureFiles()
}

function ensureFiles() {
  const pid = sessions.projectId
  if (!pid) {
    return
  }
  const key = `${pid}|${sid.value ?? ''}`
  if (atLoadedFor !== key) {
    atLoadedFor = key
    api.chatFiles(pid, sid.value).then(
      (r) => (atAll.value = r.files),
      () => (atAll.value = []), // old server / transient error — type on without the popup
    )
  }
}

/** Replace the @token at the caret with the picked path and park the caret after it. */
function acceptAt(path: string) {
  const el = ta.value
  const caret = el?.selectionStart ?? text.value.length
  const before = text.value.slice(0, atStart.value)
  const after = text.value.slice(caret)
  const inserted = `@${path} `
  text.value = before + inserted + after
  atFrag.value = null
  void nextTick(() => {
    autoGrow()
    const pos = before.length + inserted.length
    el?.focus()
    el?.setSelectionRange(pos, pos)
  })
}

function baseOf(p: string): string {
  return p.slice(p.lastIndexOf('/') + 1)
}

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
  conv.cancelEditResend() // an armed fork point belongs to the session it was armed in
  atFrag.value = null // the @-listing is per-cwd; ensureFiles refetches on the next "@"
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
  updateAtFragment()
}

function onKeydown(e: KeyboardEvent) {
  if (e.isComposing) {
    return // IME composition owns Enter and the arrows
  }
  // The slash popup owns the keys while it is open (it never overlaps history recall — recall
  // needs an EMPTY composer, the popup needs text starting with "/").
  if (slashOpen.value) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const n = slashItems.value.length
      slashIndex.value = (slashIndex.value + (e.key === 'ArrowDown' ? 1 : n - 1)) % n
      return
    }
    if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
      e.preventDefault()
      acceptSlash(slashItems.value[slashIndex.value])
      return
    }
    if (e.key === 'Escape') {
      slashDismissed.value = true
      return
    }
  }
  if (atOpen.value) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const n = atItems.value.length
      atIndex.value = (atIndex.value + (e.key === 'ArrowDown' ? 1 : n - 1)) % n
      return
    }
    if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
      e.preventDefault()
      acceptAt(atItems.value[atIndex.value])
      return
    }
    if (e.key === 'Escape') {
      atDismissed.value = true
      return
    }
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
    <div v-if="slashOpen" class="slash-pop" role="listbox">
      <button
        v-for="(c, i) in slashItems"
        :key="`${c.source}:${c.name}`"
        type="button"
        class="slash-item"
        :class="{ sel: i === slashIndex }"
        @mousedown.prevent="acceptSlash(c)"
        @mousemove="slashIndex = i"
      >
        <span class="s-name">/{{ c.name }}</span>
        <span v-if="c.argumentHint" class="s-hint">{{ c.argumentHint }}</span>
        <span class="s-desc">{{ c.description }}</span>
        <span class="s-badge">{{ c.source === 'project' ? 'project' : c.kind }}</span>
      </button>
    </div>
    <div v-if="atOpen" class="slash-pop" role="listbox">
      <button
        v-for="(p, i) in atItems"
        :key="p"
        type="button"
        class="slash-item"
        :class="{ sel: i === atIndex }"
        @mousedown.prevent="acceptAt(p)"
        @mousemove="atIndex = i"
      >
        <span class="s-name">{{ baseOf(p) }}</span>
        <span class="s-desc">@{{ p }}</span>
      </button>
    </div>
    <div v-if="conv.editArmed" class="edit-note">
      <span>✎ Edit &amp; resend — sending will fork the conversation before this message</span>
      <button class="note-x" type="button" title="Cancel edit &amp; resend" @click="conv.cancelEditResend()">✕</button>
    </div>
    <div v-if="slashHint" class="arg-hint faint">
      <span class="ah-name">/{{ slashHint.name }}</span>
      <em>{{ slashHint.hint }}</em>
    </div>
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
              ? 'Message Claude…  (Enter to send, paste/drop images, ↑ history, / commands)'
              : 'Message Claude…  (Enter to send, Shift+Enter newline, ↑ history, / commands)'
        "
        @input="onInput"
        @keydown="onKeydown"
        @keyup="updateAtFragment"
        @click="updateAtFragment"
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
  position: relative; /* anchors the slash popup just above the input row */
  flex: none;
  border-top: 1px solid var(--border);
  padding: 12px 18px 14px;
  background: var(--bg0);
}
.slash-pop {
  position: absolute;
  bottom: 100%;
  left: 18px;
  right: 18px;
  margin-bottom: 8px;
  display: flex;
  flex-direction: column;
  max-height: 280px;
  overflow-y: auto;
  padding: 4px;
  background: var(--bg0);
  border: 1px solid var(--border);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
  z-index: 20;
}
.slash-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  width: 100%;
  padding: 6px 9px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--text-dim);
  font-family: var(--ui);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}
.slash-item.sel {
  background: var(--tool-bg);
  color: var(--text);
}
.s-name {
  color: var(--accent);
  font-weight: 600;
  white-space: nowrap;
}
.s-hint {
  opacity: 0.75;
  font-style: italic;
  white-space: nowrap;
}
.s-desc {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.s-badge {
  flex: none;
  font-size: 10px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  opacity: 0.6;
}
.arg-hint {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 6px;
  font-size: 11.5px;
}
.ah-name {
  color: var(--accent);
  font-weight: 600;
}
.edit-note {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  padding: 6px 10px;
  border: 1px dashed var(--accent);
  border-radius: 8px;
  color: var(--text-dim);
  font-size: 12px;
}
.note-x {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--text-dim);
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
}
.note-x:hover {
  color: var(--accent);
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
