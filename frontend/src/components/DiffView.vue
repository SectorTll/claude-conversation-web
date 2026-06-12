<script setup lang="ts">
import { computed, ref } from 'vue'
import { buildDiffRows, collapseRows, type FoldedRow } from '@/lib/diff'

const props = defineProps<{
  filePath?: string | null
  oldText: string | null
  newText: string | null
}>()

const MAX_VISIBLE = 40

const expanded = ref(false)
const rows = computed(() => buildDiffRows(props.oldText ?? null, props.newText ?? null))
const folded = computed<FoldedRow[]>(() => collapseRows(rows.value))
const shown = computed<FoldedRow[]>(() =>
  expanded.value ? rows.value : folded.value.slice(0, MAX_VISIBLE),
)
const hiddenCount = computed(() => {
  if (expanded.value) {
    return 0
  }
  const visible = shown.value.reduce((n, r) => n + (r.folded ? 0 : 1), 0)
  return rows.value.length - visible
})
const stats = computed(() => {
  let add = 0
  let del = 0
  for (const r of rows.value) {
    if (r.sign === '+') {
      add++
    } else if (r.sign === '-') {
      del++
    }
  }
  return { add, del }
})
</script>

<template>
  <div class="diff">
    <div class="diff-head">
      <span v-if="filePath" class="diff-path">{{ filePath }}</span>
      <span class="diff-stats">
        <span v-if="stats.add" class="add">+{{ stats.add }}</span>
        <span v-if="stats.del" class="del">−{{ stats.del }}</span>
      </span>
    </div>
    <div class="diff-body">
      <template v-for="(r, i) in shown" :key="i">
        <div v-if="r.folded" class="row fold" @click="expanded = true">⋯ {{ r.folded }} unchanged lines</div>
        <div v-else class="row" :class="{ add: r.sign === '+', del: r.sign === '-' }">
          <span class="sign">{{ r.sign }}</span><span class="text">{{ r.text }}</span>
        </div>
      </template>
    </div>
    <button v-if="hiddenCount > 0" class="more" type="button" @click="expanded = true">
      show {{ hiddenCount }} more lines
    </button>
  </div>
</template>

<style scoped>
.diff {
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg0);
  margin-top: 6px;
  overflow: hidden;
}
.diff-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding: 5px 10px;
  border-bottom: 1px solid var(--border);
}
.diff-path {
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--text-dim);
  word-break: break-all;
}
.diff-stats {
  display: flex;
  gap: 7px;
  font-family: var(--mono);
  font-size: 11.5px;
  white-space: nowrap;
}
.diff-stats .add {
  color: var(--diff-add-fg);
}
.diff-stats .del {
  color: var(--diff-del-fg);
}
.diff-body {
  font-family: var(--mono);
  font-size: 12px;
  max-height: 360px;
  overflow: auto;
}
.row {
  display: flex;
  padding: 0 10px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-dim);
}
.row.add {
  background: var(--diff-add-bg);
  color: var(--diff-add-fg);
}
.row.del {
  background: var(--diff-del-bg);
  color: var(--diff-del-fg);
}
.row.fold {
  color: var(--text-faint);
  cursor: pointer;
  font-size: 11px;
  padding: 2px 10px;
}
.row.fold:hover {
  color: var(--text-dim);
}
.sign {
  width: 14px;
  flex: none;
  user-select: none;
}
.text {
  flex: 1;
  min-width: 0;
}
.more {
  display: block;
  width: 100%;
  padding: 4px 10px;
  border: none;
  border-top: 1px solid var(--border);
  background: var(--tool-bg);
  color: var(--text-faint);
  font-family: var(--ui);
  font-size: 11.5px;
  cursor: pointer;
  text-align: left;
}
.more:hover {
  color: var(--accent);
}
</style>
