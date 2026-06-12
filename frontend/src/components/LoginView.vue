<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const password = ref('')

async function submit() {
  if (!password.value || auth.busy) {
    return
  }
  try {
    await auth.login(password.value)
  } catch {
    password.value = ''
  }
}
</script>

<template>
  <div class="login">
    <form class="card" @submit.prevent="submit">
      <div class="logo">✱</div>
      <div class="title">Claude Conversations</div>
      <div class="subtitle faint">Enter the password to continue</div>
      <input
        v-model="password"
        class="input"
        type="password"
        placeholder="Password"
        autofocus
        :disabled="auth.busy"
      />
      <div v-if="auth.error" class="error">{{ auth.error }}</div>
      <button class="btn btn-accent" type="submit" :disabled="auth.busy || !password">
        {{ auth.busy ? 'Signing in…' : 'Sign in' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login {
  height: 100%;
  display: grid;
  place-items: center;
  background: var(--bg0);
}
.card {
  width: 320px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 28px 26px;
  background: var(--bg1);
  border: 1px solid var(--border);
  border-radius: 14px;
  text-align: center;
}
.logo {
  width: 44px;
  height: 44px;
  margin: 0 auto 2px;
  border-radius: 11px;
  background: var(--accent);
  color: #1a1410;
  display: grid;
  place-items: center;
  font-size: 24px;
  font-weight: 700;
}
.title {
  font-weight: 600;
  font-size: 15px;
}
.subtitle {
  font-size: 12px;
  margin-bottom: 4px;
}
.error {
  color: var(--accent);
  font-size: 12.5px;
  text-align: left;
}
.btn-accent {
  justify-content: center;
  margin-top: 2px;
}
</style>
