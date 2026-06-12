<script setup lang="ts">
import { ref } from 'vue'
import { useSessionsStore } from '@/stores/sessions'
import { useSearchStore } from '@/stores/search'
import { useProjectsStore } from '@/stores/projects'
import { useActionsStore } from '@/stores/actions'
import { useUiStore } from '@/stores/ui'
import type { SearchHit, SessionInfo } from '@/types/models'

const sessions = useSessionsStore()
const search = useSearchStore()
const projects = useProjectsStore()
const actions = useActionsStore()
const ui = useUiStore()

interface MenuState {
  x: number
  y: number
  session: SessionInfo
}
const menu = ref<MenuState | null>(null)

function onContext(e: MouseEvent, s: SessionInfo) {
  menu.value = { x: e.clientX, y: e.clientY, session: s }
}
function closeMenu() {
  menu.value = null
}
function pid(): string {
  return projects.selectedId ?? sessions.projectId ?? ''
}
function rename(s: SessionInfo) {
  ui.openRename({ projectId: pid(), session: s })
  closeMenu()
}
function run(fn: () => void) {
  fn()
  closeMenu()
}
function openHit(h: SearchHit) {
  search.openHit(h)
}
</script>

<template>
  <div class="pane" @click="closeMenu">
    <!-- Global search results -->
    <template v-if="search.active">
      <div class="pane-label">
        Search results
        <span v-if="search.loading" class="spinner pane-spinner"></span>
        <button
          class="pane-collapse push-right"
          title="Collapse sessions"
          @click="ui.togglePane('sessions')"
        >
          ◂
        </button>
      </div>
      <div class="list">
        <div
          v-for="h in search.results"
          :key="h.projectId + '/' + h.session.sessionId"
          class="row"
          :class="{ selected: h.session.sessionId === search.selectedSessionId }"
          @click="openHit(h)"
        >
          <div class="title">{{ h.title }}</div>
          <div class="snippet faint">{{ h.snippet }}</div>
          <div class="meta faint">{{ h.metaLine }}</div>
        </div>
        <div v-if="search.loading" class="loading-row">
          <span class="spinner"></span>
          Searching “{{ search.query.trim() }}”…
        </div>
        <div v-else-if="search.results.length === 0" class="empty faint">No matches</div>
      </div>
    </template>

    <!-- Sessions of the selected project -->
    <template v-else>
      <div class="pane-label sessions-head">
        <button class="mback" title="Back to projects" @click.stop="projects.selectedId = null">
          ←
        </button>
        <span>Sessions</span>
        <span v-if="sessions.loading" class="spinner pane-spinner"></span>
        <button
          class="btn btn-accent newchat"
          :disabled="!projects.selectedId"
          title="Start a new chat with Claude in this project"
          @click.stop="sessions.startDraft()"
        >
          ＋ New chat
        </button>
        <button class="pane-collapse" title="Collapse sessions" @click="ui.togglePane('sessions')">
          ◂
        </button>
      </div>
      <div class="list">
        <div
          v-for="s in sessions.items"
          :key="s.sessionId"
          class="row"
          :class="{ selected: s.sessionId === sessions.selectedId }"
          @click="sessions.select(s.sessionId)"
          @contextmenu.prevent="onContext($event, s)"
        >
          <div class="title-row">
            <span v-if="s.hasCustomTitle" class="pencil accent" title="custom name">✎</span>
            <span class="title">{{ s.title }}</span>
            <span
              v-if="s.liveStatus !== 'NONE'"
              class="badge"
              :class="{
                'b-waiting': s.liveStatus === 'WAITING',
                'b-working': s.liveStatus === 'WORKING',
                'b-idle': s.liveStatus === 'IDLE',
              }"
            >
              <span
                class="dot"
                :class="{
                  'dot-waiting': s.liveStatus === 'WAITING',
                  'dot-working': s.liveStatus === 'WORKING',
                  'dot-idle': s.liveStatus === 'IDLE',
                }"
              ></span>
              {{ s.liveStatus === 'WAITING' ? 'waiting' : s.liveStatus === 'IDLE' ? 'idle' : 'working' }}
            </span>
          </div>
          <div v-if="s.preview" class="preview faint">{{ s.preview }}</div>
          <div class="meta faint">{{ s.metaLine }}</div>
        </div>
        <div v-if="sessions.loading" class="loading-row">
          <span class="spinner"></span>
          Loading sessions…
        </div>
        <div v-else-if="sessions.items.length === 0" class="empty faint">
          {{ projects.selectedId ? 'No sessions' : 'Select a project' }}
        </div>
      </div>
    </template>

    <!-- Context menu -->
    <div
      v-if="menu"
      class="ctx"
      :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
      @click.stop
    >
      <button @click="run(() => actions.resume(pid(), menu!.session.sessionId, false))">
        ▶ Resume in CLI
      </button>
      <button @click="run(() => actions.resume(pid(), menu!.session.sessionId, true))">
        ⑂ Resume as new branch
      </button>
      <button @click="rename(menu.session)">✎ Rename…</button>
      <div class="sep"></div>
      <button @click="run(() => actions.openFolder(pid()))">📂 Open project folder</button>
      <button @click="run(() => actions.copyResumeCommand(menu!.session.sessionId))">
        ⧉ Copy resume command
      </button>
      <button @click="run(() => actions.revealFile(pid(), menu!.session.sessionId))">
        🔎 Reveal .jsonl file
      </button>
    </div>
  </div>
</template>

<style scoped>
.pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg0);
  padding-top: 8px;
}
.pane-label {
  display: flex;
  align-items: center;
  gap: 8px;
}
.sessions-head .newchat {
  margin-left: auto;
  padding: 3px 10px;
  font-size: 11px;
  letter-spacing: 0;
  text-transform: none;
}
/* Mobile-only back to the projects pane (the panes show one at a time there). */
.mback {
  display: none;
  border: none;
  background: none;
  color: var(--text-dim);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  padding: 0 4px;
}
.mback:hover {
  color: var(--accent);
}
@media (max-width: 880px) {
  .mback {
    display: inline-block;
  }
}
.push-right {
  margin-left: auto;
}
.pane-spinner {
  width: 11px;
  height: 11px;
  border-width: 2px;
}
.list {
  flex: 1;
  overflow: auto;
  padding: 0 8px 8px;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.pencil {
  font-size: 11px;
}
.title {
  font-weight: 600;
  font-size: 13px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
}
.b-waiting {
  color: var(--status-waiting);
}
.b-working {
  color: var(--status-working);
}
.b-idle {
  color: var(--status-idle);
}
.preview {
  font-size: 11.5px;
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.snippet {
  font-size: 11.5px;
  margin-top: 3px;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.meta {
  font-size: 11px;
  margin-top: 5px;
}
.empty {
  padding: 20px 10px;
  text-align: center;
  font-size: 12px;
}
.ctx {
  position: fixed;
  z-index: 100;
  background: var(--bg2);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 5px;
  min-width: 210px;
  box-shadow: 0 8px 26px rgba(0, 0, 0, 0.45);
}
.ctx button {
  display: block;
  width: 100%;
  text-align: left;
  background: none;
  border: none;
  color: var(--text);
  padding: 7px 9px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12.5px;
  font-family: var(--ui);
}
.ctx button:hover {
  background: var(--bg3);
}
.sep {
  height: 1px;
  background: var(--border);
  margin: 4px 2px;
}
</style>
