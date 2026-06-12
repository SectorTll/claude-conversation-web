<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch } from 'vue'
import AppHeader from './components/AppHeader.vue'
import StatusBar from './components/StatusBar.vue'
import ProjectsPane from './components/ProjectsPane.vue'
import SessionsPane from './components/SessionsPane.vue'
import ConversationPane from './components/ConversationPane.vue'
import RenameDialog from './components/RenameDialog.vue'
import ScheduleModal from './components/ScheduleModal.vue'
import ServerScheduleModal from './components/ServerScheduleModal.vue'
import CertHelpDialog from './components/CertHelpDialog.vue'
import TelegramSettingsDialog from './components/TelegramSettingsDialog.vue'
import LoginView from './components/LoginView.vue'
import { useProjectsStore } from './stores/projects'
import { useLiveStore } from './stores/live'
import { useAuthStore } from './stores/auth'
import { useUiStore } from './stores/ui'
import { installDeepLinks } from './lib/deepLink'

const projects = useProjectsStore()
const live = useLiveStore()
const auth = useAuthStore()
const ui = useUiStore()

onMounted(() => auth.check())

// Load data / open the SSE stream only while authenticated. Gating connect() here keeps the
// EventSource from opening (and 401-reconnect-looping) when logged out, and reconnects after login.
let deepLinksInstalled = false
watch(
  () => auth.authenticated,
  async (ok) => {
    if (ok) {
      await projects.load()
      live.connect()
      // Push-notification deep links (#/p/…/s/… or a SW message) need the project list first.
      if (!deepLinksInstalled) {
        deepLinksInstalled = true
        installDeepLinks()
      }
    } else {
      live.disconnect()
    }
  },
  { immediate: true },
)
onBeforeUnmount(() => live.disconnect())
</script>

<template>
  <div v-if="!auth.checked" class="boot faint">Loading…</div>
  <LoginView v-else-if="!auth.authenticated" />
  <div v-else class="app">
    <AppHeader />
    <div class="body">
      <div class="col projects" :class="{ collapsed: ui.projectsCollapsed }">
        <ProjectsPane v-if="!ui.projectsCollapsed" />
        <button v-else class="rail" title="Expand projects" @click="ui.togglePane('projects')">
          <span>▸</span>
          <span class="rail-label">Projects</span>
        </button>
      </div>
      <div class="divider"></div>
      <div class="col sessions" :class="{ collapsed: ui.sessionsCollapsed }">
        <SessionsPane v-if="!ui.sessionsCollapsed" />
        <button v-else class="rail" title="Expand sessions" @click="ui.togglePane('sessions')">
          <span>▸</span>
          <span class="rail-label">Sessions</span>
        </button>
      </div>
      <div class="divider"></div>
      <div class="col conversation">
        <ConversationPane />
      </div>
    </div>
    <StatusBar />
    <RenameDialog />
    <ScheduleModal />
    <ServerScheduleModal />
    <CertHelpDialog />
    <TelegramSettingsDialog />
  </div>
</template>

<style scoped>
.boot {
  height: 100%;
  display: grid;
  place-items: center;
  font-size: 13px;
}
.app {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.col {
  height: 100%;
  min-height: 0;
}
.projects {
  width: 300px;
  flex: none;
}
.sessions {
  width: 350px;
  flex: none;
}
.col.collapsed {
  width: 30px;
}
.conversation {
  flex: 1;
  min-width: 0;
}
.rail {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  background: var(--bg0);
  border: none;
  cursor: pointer;
  color: var(--text-faint);
  font-family: var(--ui);
  font-size: 11px;
  transition: background 0.12s, color 0.12s;
}
.rail:hover {
  background: var(--bg2);
  color: var(--text);
}
.rail-label {
  writing-mode: vertical-rl;
  letter-spacing: 0.09em;
  text-transform: uppercase;
  font-weight: 600;
}
.divider {
  width: 1px;
  background: var(--border);
  flex: none;
}
</style>
