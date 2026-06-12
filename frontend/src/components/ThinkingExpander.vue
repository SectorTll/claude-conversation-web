<script setup lang="ts">
defineProps<{ text: string }>()
</script>

<template>
  <details class="thinking">
    <summary>thinking</summary>
    <div class="thinking-body">{{ text }}</div>
  </details>
</template>

<style scoped>
.thinking {
  margin-bottom: 8px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--tool-bg);
}
summary {
  cursor: pointer;
  padding: 6px 10px;
  font-size: 11px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-faint);
  user-select: none;
}
.thinking-body {
  padding: 0 12px 10px;
  color: var(--text-dim);
  font-size: 12.5px;
  white-space: pre-wrap;
  max-height: 220px;
  overflow: auto;
}
</style>
