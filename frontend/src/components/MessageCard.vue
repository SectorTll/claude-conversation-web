<script setup lang="ts">
import type { ChatMessage } from '@/types/models'
import { useConversationStore } from '@/stores/conversation'
import MarkdownView from './MarkdownView.vue'
import ThinkingExpander from './ThinkingExpander.vue'
import ToolBlockView from './ToolBlockView.vue'
import QuestionView from './QuestionView.vue'
import PermissionView from './PermissionView.vue'

const props = defineProps<{ msg: ChatMessage }>()
const conv = useConversationStore()

function onAnswer(text: string, selections: string[][]) {
  conv.answerQuestion(props.msg, text, selections)
}
</script>

<template>
  <div class="msg" :class="{ user: msg.user, assistant: msg.assistant, tool: msg.tool }">
    <div class="card">
      <div class="head" :title="msg.tokensDisplay ?? ''">
        <span class="role">{{ msg.roleHeader }}</span>
        <span class="time faint">{{ msg.timeDisplay }}</span>
        <button
          v-if="msg.uuid"
          class="forkbtn"
          type="button"
          title="Fork from here — a new session containing the conversation up to this message"
          @click="conv.branchFrom(msg)"
        >
          ⑂
        </button>
      </div>
      <div v-if="msg.attachments?.length" class="atts">
        <img v-for="(a, i) in msg.attachments" :key="i" class="att" :src="a.dataUrl" alt="" />
      </div>
      <ThinkingExpander v-if="msg.hasThinking" :text="msg.thinking ?? ''" />
      <MarkdownView v-if="msg.hasText" :text="msg.text" />
      <ToolBlockView v-for="(t, i) in msg.tools" :key="i" :block="t" />
      <PermissionView
        v-for="p in msg.permissions ?? []"
        :key="p.requestId"
        :permission="p"
        @decide="(allow, always) => conv.decidePermission(p, allow, { always })"
      />
      <QuestionView v-if="msg.question" :question="msg.question" @answer="onAnswer" />
    </div>
  </div>
</template>

<style scoped>
.msg {
  display: flex;
  margin-bottom: 14px;
}
.msg.user {
  justify-content: flex-end;
}
.card {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 13px 15px;
  background: var(--bg1);
  max-width: 900px;
}
.msg.user .card {
  background: var(--user-bubble);
  border-color: #3a342c;
  max-width: 640px;
}
.msg.tool .card {
  background: var(--tool-bg);
}
.head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 7px;
}
.role {
  color: var(--accent);
  font-weight: 600;
  font-size: 12.5px;
}
.msg.tool .role {
  color: var(--text-faint);
}
.time {
  font-size: 11px;
}
.forkbtn {
  margin-left: auto;
  padding: 0 5px;
  border: none;
  background: none;
  color: var(--text-faint);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s;
}
.card:hover .forkbtn {
  opacity: 1;
}
.forkbtn:hover {
  color: var(--accent);
}
.atts {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.att {
  max-height: 180px;
  max-width: 280px;
  border: 1px solid var(--border);
  border-radius: 8px;
  object-fit: contain;
}
</style>
