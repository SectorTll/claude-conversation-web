import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, ApiError } from '@/api/client'

/**
 * Shared-password auth. `check()` probes the server on startup; `login()` posts the password and the
 * backend sets the session cookie. Any 401 from any API call flips `authenticated` to false (see the
 * central handler in api/client.ts), which sends the app back to the login screen.
 */
export const useAuthStore = defineStore('auth', () => {
  const authenticated = ref(false)
  const checked = ref(false)
  const busy = ref(false)
  const error = ref<string | null>(null)

  async function check() {
    try {
      const res = await api.authStatus()
      authenticated.value = res.authenticated
    } catch {
      authenticated.value = false
    } finally {
      checked.value = true
    }
  }

  async function login(password: string) {
    error.value = null
    busy.value = true
    try {
      await api.login(password)
      authenticated.value = true
    } catch (e) {
      authenticated.value = false
      error.value =
        e instanceof ApiError && e.status === 401 ? 'Wrong password' : (e as Error).message
      throw e
    } finally {
      busy.value = false
    }
  }

  async function logout() {
    try {
      await api.logout()
    } finally {
      authenticated.value = false
    }
  }

  /** Called centrally when any request returns 401 (e.g. the session expired). */
  function markLoggedOut() {
    authenticated.value = false
  }

  return { authenticated, checked, busy, error, check, login, logout, markLoggedOut }
})
