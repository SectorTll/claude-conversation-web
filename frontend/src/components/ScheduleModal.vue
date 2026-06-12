<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useScheduleStore } from '@/stores/schedule'
import type { ScheduleKind, TaskSpec } from '@/types/models'

const ui = useUiStore()
const schedule = useScheduleStore()

const DOW: [string, string][] = [
  ['MONDAY', 'Mon'],
  ['TUESDAY', 'Tue'],
  ['WEDNESDAY', 'Wed'],
  ['THURSDAY', 'Thu'],
  ['FRIDAY', 'Fri'],
  ['SATURDAY', 'Sat'],
  ['SUNDAY', 'Sun'],
]

function localDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
    d.getDate(),
  ).padStart(2, '0')}`
}

const name = ref('')
const workingDir = ref('')
const prompt = ref('')
const allowedTools = ref('Bash Read Write Edit Glob Grep')
const kind = ref<ScheduleKind>('DAILY')
const time = ref('09:00')
const date = ref(localDate(new Date(Date.now() + 86_400_000)))
const days = reactive<Record<string, boolean>>({
  MONDAY: true,
  TUESDAY: true,
  WEDNESDAY: true,
  THURSDAY: true,
  FRIDAY: true,
  SATURDAY: false,
  SUNDAY: false,
})
const hidden = ref(true)
const openReport = ref(true)
const timeLimitHours = ref(2)
const formError = ref('')
const creating = ref(false)

watch(
  () => ui.scheduleOpen,
  (open) => {
    if (open) {
      formError.value = ''
      schedule.loadCapabilities()
      schedule.load()
    }
  },
)

async function submit() {
  formError.value = ''
  if (!name.value.trim()) {
    formError.value = 'Name is required'
    return
  }
  if (!prompt.value.trim()) {
    formError.value = 'Prompt is required'
    return
  }
  if (!/^\d{1,2}:\d{2}$/.test(time.value)) {
    formError.value = 'Time must be HH:mm'
    return
  }
  const selectedDays = DOW.map(([k]) => k).filter((k) => days[k])
  if (kind.value === 'WEEKLY' && selectedDays.length === 0) {
    formError.value = 'Pick at least one weekday'
    return
  }
  const day = kind.value === 'ONCE' ? date.value : localDate(new Date())
  const spec: TaskSpec = {
    name: name.value.trim(),
    prompt: prompt.value,
    workingDir: workingDir.value.trim(),
    allowedTools: allowedTools.value.trim(),
    kind: kind.value,
    at: `${day}T${time.value.padStart(5, '0')}:00`,
    days: selectedDays,
    timeLimitHours: Number(timeLimitHours.value) || 2,
    hidden: hidden.value,
    openReport: openReport.value,
  }
  creating.value = true
  const ok = await schedule.create(spec)
  creating.value = false
  if (ok) {
    name.value = ''
    prompt.value = ''
  }
}
</script>

<template>
  <div v-if="ui.scheduleOpen" class="overlay" @click.self="ui.scheduleOpen = false">
    <div class="dialog">
      <div class="head">
        <div class="title">Scheduled Claude tasks</div>
        <button class="x" @click="ui.scheduleOpen = false">✕</button>
      </div>

      <div v-if="!schedule.supported" class="warn">
        Task scheduling needs Windows Task Scheduler — it is not available on this server's OS.
      </div>

      <!-- Existing tasks -->
      <div class="section-label">Existing tasks</div>
      <div class="tasks">
        <div v-if="schedule.tasks.length === 0" class="empty faint">No scheduled tasks yet</div>
        <div v-for="t in schedule.tasks" :key="t.name" class="task">
          <div class="task-main">
            <div class="task-name">{{ t.name }}</div>
            <div class="task-sub faint">{{ t.subLine }}</div>
            <div class="task-prompt faint">{{ t.promptPreview }}</div>
          </div>
          <div class="task-actions">
            <button class="btn btn-ghost" @click="schedule.run(t.name)">Run</button>
            <button class="btn btn-ghost" @click="schedule.open(t.name)">Open</button>
            <button class="btn btn-ghost danger" @click="schedule.remove(t.name)">Delete</button>
          </div>
        </div>
      </div>

      <!-- New task -->
      <div class="section-label">New task</div>
      <fieldset class="form" :disabled="!schedule.supported || creating">
        <label>Name<input v-model="name" class="input" placeholder="Daily triage" /></label>
        <label>
          Working directory
          <input v-model="workingDir" class="input" placeholder="C:\Repos\my-project (optional)" />
        </label>
        <label>
          Prompt
          <textarea v-model="prompt" class="input area" rows="4" placeholder="What should Claude do?"></textarea>
        </label>
        <label>
          Allowed tools
          <input v-model="allowedTools" class="input" />
        </label>

        <div class="row-fields">
          <div class="kind">
            <label class="radio"><input v-model="kind" type="radio" value="DAILY" /> Daily</label>
            <label class="radio"><input v-model="kind" type="radio" value="WEEKLY" /> Weekly</label>
            <label class="radio"><input v-model="kind" type="radio" value="ONCE" /> Once</label>
          </div>
          <label class="time">Time<input v-model="time" class="input" placeholder="09:00" /></label>
          <label v-if="kind === 'ONCE'" class="date">
            Date<input v-model="date" class="input" type="date" />
          </label>
          <label class="tl">
            Time limit (h)<input v-model="timeLimitHours" class="input" type="number" min="1" />
          </label>
        </div>

        <div v-if="kind === 'WEEKLY'" class="days">
          <label v-for="[k, lbl] in DOW" :key="k" class="day">
            <input v-model="days[k]" type="checkbox" /> {{ lbl }}
          </label>
        </div>

        <div class="checks">
          <label><input v-model="hidden" type="checkbox" /> Run hidden</label>
          <label><input v-model="openReport" type="checkbox" /> Open report when done</label>
        </div>

        <div class="form-footer">
          <span class="form-error">{{ formError }}</span>
          <button class="btn btn-accent" type="button" :disabled="creating" @click="submit">
            {{ creating ? 'Creating…' : 'Create task' }}
          </button>
        </div>
      </fieldset>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: grid;
  place-items: center;
  z-index: 200;
}
.dialog {
  width: 680px;
  max-height: 88vh;
  overflow: auto;
  background: var(--bg1);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px 22px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.title {
  font-weight: 600;
  font-size: 16px;
}
.x {
  background: none;
  border: none;
  color: var(--text-faint);
  cursor: pointer;
  font-size: 15px;
}
.x:hover {
  color: var(--text);
}
.warn {
  margin: 12px 0;
  padding: 9px 12px;
  border: 1px solid var(--status-waiting);
  color: var(--status-waiting);
  border-radius: 8px;
  font-size: 12.5px;
}
.section-label {
  margin: 18px 0 8px;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-faint);
  font-weight: 600;
}
.tasks {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.empty {
  padding: 14px;
  text-align: center;
  font-size: 12.5px;
}
.task {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--bg0);
}
.task-name {
  font-weight: 600;
  font-size: 13px;
}
.task-sub,
.task-prompt {
  font-size: 11.5px;
  margin-top: 2px;
}
.task-actions {
  display: flex;
  gap: 6px;
  flex: none;
}
.danger:hover {
  color: #e06a5a;
  border-color: #e06a5a;
}
.form {
  border: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.form label {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12px;
  color: var(--text-dim);
}
.area {
  resize: vertical;
  font-family: var(--ui);
}
.row-fields {
  display: flex;
  gap: 14px;
  align-items: flex-end;
  flex-wrap: wrap;
}
.kind {
  display: flex;
  gap: 12px;
}
.radio,
.day,
.checks label {
  flex-direction: row !important;
  align-items: center;
  gap: 5px;
  color: var(--text);
}
.time .input,
.date .input,
.tl .input {
  width: 110px;
}
.days {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.checks {
  display: flex;
  gap: 20px;
}
.form-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  margin-top: 6px;
}
.form-error {
  color: #e06a5a;
  font-size: 12px;
  margin-right: auto;
}
</style>
