<script setup lang="ts">
import { computed } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useLiveStore } from '@/stores/live'

const ui = useUiStore()
const live = useLiveStore()
const waiting = computed(() => live.waitingCount)
</script>

<template>
  <footer class="status">
    <div class="left">{{ ui.status }}</div>
    <div class="right">
      <span v-if="waiting > 0" class="waiting">
        <span class="dot dot-waiting"></span>
        {{ waiting }} waiting for input
      </span>
      <span v-if="ui.busy" class="accent">working…</span>
      <span v-if="!live.connected" class="faint">reconnecting…</span>
    </div>
  </footer>
</template>

<style scoped>
.status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 16px;
  background: var(--bg1);
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-dim);
}
.right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.waiting {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--status-waiting);
}
</style>
