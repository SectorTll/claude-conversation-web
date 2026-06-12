<script setup lang="ts">
import { computed } from 'vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()

// The downloads only exist when the server actually runs TLS (prod). On plain-http dev the
// origin (localhost) is already a secure context, so this dialog is informational there.
const httpsOrigin = computed(() => window.location.protocol === 'https:')
</script>

<template>
  <div v-if="ui.certHelpOpen" class="overlay" @click.self="ui.certHelpOpen = false">
    <div class="dialog">
      <div class="title">Trust this server's certificate</div>
      <div class="hint faint">
        Push notifications need the browser to <b>trust</b> this server — accepting the security
        warning is not enough for a service worker. One-time setup per device:
      </div>

      <template v-if="httpsOrigin">
        <ol class="steps">
          <li>
            Download and run
            <a class="dl" href="/cert/install-cert.bat" download>install-cert.bat</a>
            — it imports the certificate for your Windows user (one confirmation dialog).
          </li>
          <li><b>Restart the browser</b> (all windows).</li>
          <li>Press the 📲 button again — push should now switch on.</li>
        </ol>
        <div class="alt faint">
          Prefer doing it by hand (or on macOS/Linux)? Download the bare certificate:
          <a class="dl" href="/cert/claudeweb.cer" download>claudeweb.cer</a>
          and add it to your system's trusted root store (Windows: double-click → install into
          “Trusted Root Certification Authorities”; macOS: Keychain Access → System → always trust).
        </div>
        <div class="alt faint">
          Firefox keeps its own certificate store — either import the .cer under Settings →
          Certificates → Authorities, or set <code>security.enterprise_roots.enabled</code> in
          <code>about:config</code> so it trusts the system store.
        </div>
      </template>
      <div v-else class="alt faint">
        This page is running over plain http (dev mode) — certificate trust doesn't apply here.
        On the packaged https server this dialog offers the certificate + a one-click installer.
      </div>

      <div class="buttons">
        <button class="btn btn-accent" @click="ui.certHelpOpen = false">Close</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: grid;
  place-items: center;
  z-index: 200;
}
.dialog {
  width: 540px;
  background: var(--bg1);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px;
}
.title {
  font-weight: 600;
  font-size: 15px;
  margin-bottom: 6px;
}
.hint {
  font-size: 12.5px;
  margin-bottom: 12px;
}
.steps {
  margin: 0 0 12px;
  padding-left: 20px;
  font-size: 13px;
  line-height: 1.7;
}
.dl {
  color: var(--accent);
  font-weight: 600;
  text-decoration: none;
}
.dl:hover {
  color: var(--accent-hover);
  text-decoration: underline;
}
.alt {
  font-size: 12px;
  line-height: 1.6;
  margin-bottom: 8px;
}
.alt code {
  font-family: var(--mono);
  font-size: 11px;
}
.buttons {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
