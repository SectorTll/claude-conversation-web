<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { api } from '@/api/client'
import { useUiStore } from '@/stores/ui'
import { useSessionsStore } from '@/stores/sessions'

const ui = useUiStore()
const sessions = useSessionsStore()

const value = ref('')
const saving = ref(false)
const inputEl = ref<HTMLInputElement | null>(null)

watch(
  () => ui.renameTarget,
  async (t) => {
    if (t) {
      value.value = t.session.title
      await nextTick()
      inputEl.value?.focus()
      inputEl.value?.select()
    }
  },
)

async function save() {
  const target = ui.renameTarget
  if (!target || !value.value.trim()) {
    return
  }
  saving.value = true
  try {
    const updated = await api.rename(target.projectId, target.session.sessionId, value.value.trim())
    sessions.replace(updated)
    ui.setStatus(`Renamed to “${updated.title}”`)
    ui.closeRename()
  } catch (e) {
    ui.setError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="ui.renameTarget" class="overlay" @click.self="ui.closeRename()">
    <div class="dialog">
      <div class="title">Rename session</div>
      <div class="hint faint">
        Writes a custom title at the Claude level — it shows up in Claude's own /resume picker.
      </div>
      <input
        ref="inputEl"
        v-model="value"
        class="input"
        @keydown.enter="save"
        @keydown.esc="ui.closeRename()"
      />
      <div class="buttons">
        <button class="btn btn-ghost" @click="ui.closeRename()">Cancel</button>
        <button class="btn btn-accent" :disabled="saving || !value.trim()" @click="save">
          Save
        </button>
      </div>
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
  width: 440px;
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
  font-size: 12px;
  margin-bottom: 14px;
}
.buttons {
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  margin-top: 16px;
}
</style>
