<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '@/api/client'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()

const loading = ref(false)
const busy = ref(false)
const message = ref('')
const messageOk = ref(true)

// Form state — mirrors TelegramSettings, plus a write-only token input that starts empty.
const enabled = ref(false)
const chatId = ref('')
const publicBaseUrl = ref('')
const tokenInput = ref('')
const tokenSet = ref(false)
const tokenFromEnv = ref(false)

// (Re)load whenever the dialog opens so it always shows the server's current state.
watch(
  () => ui.telegramOpen,
  async (open) => {
    if (!open) return
    message.value = ''
    tokenInput.value = ''
    loading.value = true
    try {
      apply(await api.telegramSettings())
    } catch (e) {
      messageOk.value = false
      message.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  },
)

function apply(s: { enabled: boolean; chatId: string; publicBaseUrl: string; tokenSet: boolean; tokenFromEnv: boolean }) {
  enabled.value = s.enabled
  chatId.value = s.chatId
  publicBaseUrl.value = s.publicBaseUrl
  tokenSet.value = s.tokenSet
  tokenFromEnv.value = s.tokenFromEnv
}

async function persist(clearToken = false) {
  return api.telegramUpdate({
    enabled: enabled.value,
    chatId: chatId.value.trim(),
    publicBaseUrl: publicBaseUrl.value.trim(),
    // Send the token only when the user actually typed one — blank keeps the existing token.
    botToken: tokenInput.value.trim() || undefined,
    clearToken,
  })
}

async function save() {
  busy.value = true
  try {
    apply(await persist())
    tokenInput.value = ''
    messageOk.value = true
    message.value = 'Saved'
  } catch (e) {
    messageOk.value = false
    message.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

async function removeToken() {
  busy.value = true
  try {
    apply(await persist(true))
    tokenInput.value = ''
    messageOk.value = true
    message.value = 'Token removed'
  } catch (e) {
    messageOk.value = false
    message.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

// Persist what's on screen first, then fire a real message so the test reflects the current form.
async function sendTest() {
  busy.value = true
  try {
    apply(await persist())
    tokenInput.value = ''
    const outcome = await api.telegramTest()
    messageOk.value = outcome.ok
    message.value = outcome.detail
  } catch (e) {
    messageOk.value = false
    message.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div v-if="ui.telegramOpen" class="overlay" @click.self="ui.telegramOpen = false">
    <div class="dialog">
      <div class="title">✈ Telegram notifications</div>
      <div class="hint faint">
        Get a ping in Telegram whenever a chat turn waits on a permission/question card — no
        certificate or service worker required. One message per card, with a per-session cooldown.
      </div>

      <div v-if="loading" class="faint loadingrow">Loading…</div>
      <template v-else>
        <label class="row check">
          <input v-model="enabled" type="checkbox" />
          <span>Enabled</span>
        </label>

        <label class="row">
          <span class="lbl">Bot token</span>
          <input
            v-model="tokenInput"
            class="input"
            type="password"
            autocomplete="off"
            spellcheck="false"
            :placeholder="tokenSet ? '•••••••• (saved — type to replace)' : 'Paste the token from @BotFather'"
          />
        </label>
        <div class="sub faint">
          <span v-if="tokenSet && tokenFromEnv">
            A token is set via the <code>CLAUDE_TELEGRAM_BOT_TOKEN</code> env var. Type one here to
            override it.
          </span>
          <span v-else-if="tokenSet">
            A token is saved. <a class="link" @click="removeToken">Remove it</a>
          </span>
          <span v-else>
            In Telegram, message <b>@BotFather</b> → <code>/newbot</code> to get a token.
          </span>
        </div>

        <label class="row">
          <span class="lbl">Chat id</span>
          <input
            v-model="chatId"
            class="input"
            type="text"
            spellcheck="false"
            placeholder="your user id or a group id"
          />
        </label>
        <div class="sub faint">
          Message your bot once, then open
          <code>api.telegram.org/bot&lt;token&gt;/getUpdates</code> and copy
          <code>result[].message.chat.id</code>.
        </div>

        <label class="row">
          <span class="lbl">Deep-link base</span>
          <input
            v-model="publicBaseUrl"
            class="input"
            type="text"
            spellcheck="false"
            placeholder="https://192.168.1.10:8443 (optional)"
          />
        </label>
        <div class="sub faint">
          Absolute origin used to make the link in each message clickable. Leave blank for no link.
        </div>

        <div v-if="message" class="status" :class="{ ok: messageOk, err: !messageOk }">
          {{ message }}
        </div>

        <div class="buttons">
          <button class="btn btn-ghost" :disabled="busy" @click="sendTest">Send test</button>
          <span class="spacer"></span>
          <button class="btn btn-ghost" @click="ui.telegramOpen = false">Close</button>
          <button class="btn btn-accent" :disabled="busy" @click="save">Save</button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: grid;
  place-items: center;
  z-index: 200;
}
.dialog {
  width: 540px;
  background: var(--bg1);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
}
.title {
  font-weight: 600;
  font-size: 15px;
  margin-bottom: 6px;
}
.hint {
  font-size: 12.5px;
  margin-bottom: 14px;
}
.loadingrow {
  font-size: 13px;
  padding: 12px 0;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.row.check {
  margin-bottom: 12px;
}
.lbl {
  width: 110px;
  flex: none;
  font-size: 12.5px;
  color: var(--text-faint);
}
.row .input {
  flex: 1;
}
.sub {
  font-size: 11.5px;
  line-height: 1.5;
  margin: 0 0 12px 120px;
}
.sub code {
  font-family: var(--mono);
  font-size: 11px;
}
.link {
  color: var(--accent);
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}
.status {
  font-size: 12.5px;
  margin: 4px 0 12px;
  padding: 8px 10px;
  border-radius: 8px;
}
.status.ok {
  background: rgba(120, 200, 120, 0.12);
  color: #8ad08a;
}
.status.err {
  background: rgba(220, 120, 100, 0.12);
  color: #e08a78;
}
.buttons {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.spacer {
  flex: 1;
}
</style>
