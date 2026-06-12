<script setup lang="ts">
import { computed, nextTick, reactive } from 'vue'
import type { PendingQuestion } from '@/types/models'

const props = defineProps<{ question: PendingQuestion }>()
// `selections` carries the raw per-question option labels — the sdk engine answers in-turn with
// them, while the cli engine sends the human-readable `text` as the next message.
const emit = defineEmits<{ (e: 'answer', text: string, selections: string[][]): void }>()

// selected[i] = the chosen option labels for question i (one entry for single-select, many for multi).
const selected = reactive<string[][]>(props.question.questions.map(() => []))
// "Other" is a synthetic free-text option appended to every question (the native CLI always offers
// it); the typed text joins the selections like any label, so both engines carry it unchanged.
const otherOn = reactive<boolean[]>(props.question.questions.map(() => false))
const otherText = reactive<string[]>(props.question.questions.map(() => ''))
const otherInputs: Record<number, HTMLInputElement | null> = {}

// A lone single-select question is the common case — answer immediately on click, no extra button.
const single = computed(
  () => props.question.questions.length === 1 && !props.question.questions[0].multiSelect,
)

function isSelected(qi: number, label: string): boolean {
  return selected[qi].includes(label)
}

function toggle(qi: number, label: string) {
  if (props.question.answered) {
    return
  }
  const q = props.question.questions[qi]
  if (q.multiSelect) {
    const cur = selected[qi]
    const at = cur.indexOf(label)
    if (at >= 0) {
      cur.splice(at, 1)
    } else {
      cur.push(label)
    }
  } else {
    selected[qi] = [label]
    otherOn[qi] = false
    if (single.value) {
      submit()
    }
  }
}

function toggleOther(qi: number) {
  if (props.question.answered) {
    return
  }
  otherOn[qi] = !otherOn[qi]
  if (otherOn[qi]) {
    if (!props.question.questions[qi].multiSelect) {
      selected[qi] = []
    }
    void nextTick(() => otherInputs[qi]?.focus())
  }
}

function picks(qi: number): string[] {
  const extra = otherOn[qi] ? otherText[qi].trim() : ''
  return extra ? [...selected[qi], extra] : [...selected[qi]]
}

// An open "Other" requires text — don't silently submit an empty custom answer.
const ready = computed(() =>
  selected.every((s, i) => (otherOn[i] ? otherText[i].trim().length > 0 : s.length > 0)),
)

function submit() {
  if (props.question.answered || !ready.value) {
    return
  }
  const qs = props.question.questions
  const all = qs.map((_, i) => picks(i))
  const text = single.value
    ? (all[0][0] ?? '')
    : qs.map((q, i) => `${q.header || q.question}: ${all[i].join(', ')}`).join('\n')
  if (text) {
    emit('answer', text, all)
  }
}
</script>

<template>
  <div class="question" :class="{ answered: question.answered }">
    <div v-for="(q, qi) in question.questions" :key="qi" class="q">
      <div v-if="q.header" class="q-head">{{ q.header }}</div>
      <div class="q-text">{{ q.question }}</div>
      <div class="opts">
        <button
          v-for="(o, oi) in q.options"
          :key="oi"
          type="button"
          class="opt"
          :class="{ on: isSelected(qi, o.label) }"
          :disabled="question.answered"
          @click="toggle(qi, o.label)"
        >
          <span class="opt-label">{{ o.label }}</span>
          <span v-if="o.description" class="opt-desc">{{ o.description }}</span>
        </button>
        <button
          type="button"
          class="opt"
          :class="{ on: otherOn[qi] }"
          :disabled="question.answered"
          @click="toggleOther(qi)"
        >
          <span class="opt-label">Other</span>
          <span class="opt-desc">Type your own answer</span>
        </button>
        <input
          v-if="otherOn[qi]"
          :ref="(el) => (otherInputs[qi] = el as HTMLInputElement | null)"
          v-model="otherText[qi]"
          class="other-input"
          type="text"
          placeholder="Your answer…"
          :disabled="question.answered"
          @keydown.enter.prevent="submit"
        />
      </div>
    </div>
    <div v-if="!single || otherOn[0]" class="actions">
      <button class="submit" type="button" :disabled="question.answered || !ready" @click="submit">
        {{ question.answered ? 'Answered' : 'Answer' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.question {
  border: 1px solid var(--accent-dim);
  border-radius: 8px;
  background: var(--tool-bg);
  margin-top: 8px;
  padding: 10px 12px;
}
.q + .q {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}
.q-head {
  font-size: 11px;
  letter-spacing: 0.03em;
  text-transform: uppercase;
  color: var(--accent);
  margin-bottom: 4px;
}
.q-text {
  color: var(--text);
  font-size: 13.5px;
  margin-bottom: 9px;
}
.opts {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.opt {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  text-align: left;
  width: 100%;
  padding: 8px 11px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg0);
  color: var(--text-dim);
  font-family: var(--ui);
  cursor: pointer;
}
.opt:hover:not(:disabled) {
  border-color: var(--accent);
}
.opt.on {
  border-color: var(--accent);
  background: var(--accent-dim);
  color: var(--text);
}
.opt:disabled {
  cursor: default;
  opacity: 0.7;
}
.opt-label {
  font-size: 13px;
  font-weight: 600;
}
.opt-desc {
  font-size: 11.5px;
  color: var(--text-faint);
}
.other-input {
  width: 100%;
  padding: 8px 11px;
  border: 1px solid var(--accent);
  border-radius: 7px;
  background: var(--bg0);
  color: var(--text);
  font-family: var(--ui);
  font-size: 13px;
}
.other-input:focus {
  outline: none;
  border-color: var(--accent-hover);
}
.other-input:disabled {
  opacity: 0.7;
}
.actions {
  margin-top: 10px;
}
.submit {
  padding: 6px 14px;
  border: 1px solid var(--accent);
  border-radius: 7px;
  background: var(--accent);
  color: #1a1714;
  font-family: var(--ui);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
}
.submit:hover:not(:disabled) {
  background: var(--accent-hover);
}
.submit:disabled {
  opacity: 0.45;
  cursor: default;
}
</style>
