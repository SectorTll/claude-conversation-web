<script setup lang="ts">
import { useProjectsStore } from '@/stores/projects'
import { useSearchStore } from '@/stores/search'
import { useUiStore } from '@/stores/ui'
import type { ProjectInfo } from '@/types/models'

const projects = useProjectsStore()
const search = useSearchStore()
const ui = useUiStore()

function open(p: ProjectInfo) {
  search.clear()
  projects.select(p.projectId)
}
</script>

<template>
  <div class="pane">
    <div class="search-box">
      <input v-model="projects.filter" class="input" placeholder="Filter projects…" />
      <button class="pane-collapse" title="Collapse projects" @click="ui.togglePane('projects')">
        ◂
      </button>
    </div>
    <div class="list">
      <div
        v-for="p in projects.filtered"
        :key="p.projectId"
        class="row"
        :class="{ selected: p.projectId === projects.selectedId }"
        @click="open(p)"
      >
        <div class="line1">
          <span class="name">{{ p.shortName }}</span>
          <span
            v-if="p.liveStatus !== 'NONE'"
            class="dot"
            :class="{
              'dot-waiting': p.liveStatus === 'WAITING',
              'dot-working': p.liveStatus === 'WORKING',
              'dot-idle': p.liveStatus === 'IDLE',
            }"
          ></span>
        </div>
        <div class="path faint">{{ p.realPath }}</div>
        <div class="meta">
          <span class="accent">{{ p.sessionCountDisplay }}</span>
          <span class="faint">{{ p.lastActivityDisplay }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg0);
}
.search-box {
  padding: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.list {
  flex: 1;
  overflow: auto;
  padding: 0 8px 8px;
}
.line1 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.name {
  font-weight: 600;
  font-size: 13.5px;
}
.path {
  font-size: 11px;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  margin-top: 5px;
}
</style>
