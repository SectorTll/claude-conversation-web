<script setup lang="ts">
import { computed } from 'vue'
import type { ToolBlock } from '@/types/models'
import DiffView from './DiffView.vue'

const props = defineProps<{ block: ToolBlock }>()

// Edit/Write carry a structured change — render a real diff instead of the JSON blob.
const isDiff = computed(() => props.block.oldText != null || props.block.newText != null)
</script>

<template>
  <div class="tool">
    <div class="tool-head">
      <span class="tool-icon">{{ block.kind === 'use' ? '🔧' : '↩' }}</span>
      <span class="tool-title">{{ block.title }}</span>
    </div>
    <DiffView
      v-if="isDiff"
      class="tool-diff"
      :file-path="block.filePath"
      :old-text="block.oldText ?? null"
      :new-text="block.newText ?? null"
    />
    <pre v-else class="tool-body">{{ block.body }}</pre>
  </div>
</template>

<style scoped>
.tool {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--tool-bg);
  margin-top: 8px;
  overflow: hidden;
}
.tool-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
}
.tool-icon {
  font-size: 12px;
}
.tool-title {
  font-family: var(--mono);
  font-size: 12px;
  color: var(--accent);
}
.tool-body {
  margin: 0;
  padding: 9px 11px;
  font-family: var(--mono);
  font-size: 12px;
  color: var(--text-dim);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow: auto;
}
.tool-diff {
  margin: 8px 10px 10px;
}
</style>
