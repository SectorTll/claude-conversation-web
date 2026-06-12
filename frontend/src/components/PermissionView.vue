<script setup lang="ts">
import { computed } from 'vue'
import type { PendingPermission } from '@/types/models'
import DiffView from './DiffView.vue'

const props = defineProps<{ permission: PendingPermission }>()
const emit = defineEmits<{ (e: 'decide', allow: boolean, always?: boolean): void }>()

// Always-allow persists the SDK's suggested rules — only offer it when there are rules to persist.
const canAlways = computed(() => (props.permission.suggestions?.length ?? 0) > 0)

// Edit/Write asks carry the change in their input — show the diff BEFORE the user approves it.
const diff = computed(() => {
  const name = props.permission.toolName
  const input = props.permission.input as Record<string, unknown> | null
  if (!input || typeof input !== 'object') {
    return null
  }
  const filePath = typeof input.file_path === 'string' ? input.file_path : null
  if (name === 'Edit' && typeof input.old_string === 'string' && typeof input.new_string === 'string') {
    return { filePath, oldText: input.old_string, newText: input.new_string }
  }
  if (name === 'Write' && typeof input.content === 'string') {
    return { filePath, oldText: null, newText: input.content }
  }
  return null
})

const inputPreview = computed(() => {
  try {
    return JSON.stringify(props.permission.input, null, 2) ?? ''
  } catch {
    return String(props.permission.input)
  }
})

const verdict = computed(() =>
  !props.permission.decided ? null : props.permission.allowed ? '✓ Allowed' : '✗ Denied',
)
</script>

<template>
  <div class="permission" :class="{ decided: permission.decided }">
    <div class="p-head">
      <span class="p-title">Permission · {{ permission.toolName }}</span>
      <span v-if="verdict" class="p-verdict" :class="{ ok: permission.allowed }">{{ verdict }}</span>
    </div>
    <DiffView v-if="diff" :file-path="diff.filePath" :old-text="diff.oldText" :new-text="diff.newText" />
    <details v-if="inputPreview && inputPreview !== '{}'" class="p-input" :class="{ secondary: diff }">
      <summary>input</summary>
      <pre>{{ inputPreview }}</pre>
    </details>
    <div v-if="!permission.decided" class="actions">
      <button class="allow" type="button" @click="emit('decide', true)">Allow</button>
      <button
        v-if="canAlways"
        class="always"
        type="button"
        title="Allow and stop asking for this tool (persists a permission rule)"
        @click="emit('decide', true, true)"
      >
        Always allow
      </button>
      <button class="deny" type="button" @click="emit('decide', false)">Deny</button>
    </div>
  </div>
</template>

<style scoped>
.permission {
  border: 1px solid var(--accent-dim);
  border-radius: 8px;
  background: var(--tool-bg);
  margin-top: 8px;
  padding: 10px 12px;
}
.permission.decided {
  opacity: 0.75;
}
.p-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.p-title {
  font-size: 12px;
  letter-spacing: 0.03em;
  text-transform: uppercase;
  color: var(--accent);
  font-weight: 600;
}
.p-verdict {
  font-size: 12px;
  color: var(--status-waiting);
}
.p-verdict.ok {
  color: var(--status-working);
}
.p-input {
  margin-top: 8px;
}
.p-input.secondary summary {
  font-size: 11px;
}
.p-input summary {
  cursor: pointer;
  font-size: 11.5px;
  color: var(--text-faint);
}
.p-input pre {
  margin: 6px 0 0;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg0);
  color: var(--text-dim);
  font-size: 11.5px;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.allow,
.always,
.deny {
  padding: 6px 14px;
  border-radius: 7px;
  font-family: var(--ui);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.allow {
  border: 1px solid var(--accent);
  background: var(--accent);
  color: #1a1714;
}
.allow:hover {
  background: var(--accent-hover);
}
.always {
  border: 1px solid var(--accent);
  background: var(--bg0);
  color: var(--accent);
}
.always:hover {
  background: var(--accent-dim);
}
.deny {
  border: 1px solid var(--border);
  background: var(--bg0);
  color: var(--text-dim);
}
.deny:hover {
  border-color: var(--status-waiting);
  color: var(--text);
}
</style>
