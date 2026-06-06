<script setup lang="ts">
useSeoMeta({
  title: 'Privacy Policy · chuchu',
  description: 'Privacy policy for the chuchu Android SSH client.',
})

const lastUpdated = 'June 5, 2026'
</script>

<template>
  <main class="min-h-screen px-4 py-6 md:py-10">
    <div class="mx-auto w-full max-w-4xl border border-border bg-bg overflow-hidden">
      <div class="tui-bar flex items-stretch justify-between border-b border-border text-xs">
        <div class="flex items-stretch">
          <span class="seg bg-accent text-onAccent font-bold">DOC</span>
          <span class="seg text-fg">chuchu</span>
          <span class="seg text-muted">/privacy</span>
        </div>
        <div class="flex items-stretch text-muted">
          <NuxtLink to="/" class="seg border-l border-border">home</NuxtLink>
        </div>
      </div>

      <article class="px-6 py-8 md:px-10 md:py-10 prose-chu">
        <h1>Privacy Policy</h1>
        <p class="text-sm text-muted">Last updated: {{ lastUpdated }}</p>

        <p>
          Chuchu is a local-first Android SSH client. It is designed to connect you directly to
          servers that you choose. Chuchu does not operate its own backend for terminal sessions,
          account management, analytics, or advertising.
        </p>

        <h2>1. Information Chuchu Stores</h2>
        <p>To provide its core features, Chuchu stores the following information on your device:</p>
        <ul>
          <li>Server profiles such as host, port, username, transport settings, and saved preferences.</li>
          <li>SSH credentials that you choose to store in the app, including passwords, key metadata, and private keys.</li>
          <li>Known host fingerprints used to warn you about SSH host key changes.</li>
          <li>App settings such as theme, accessory key layout, custom terminal actions, and lock preferences.</li>
          <li>Encrypted backup files only when you explicitly export them.</li>
        </ul>

        <h2>2. How Chuchu Uses Network Access</h2>
        <p>
          Chuchu uses network access only to connect to servers and services that you explicitly
          configure or choose to reach, such as SSH, Mosh, SFTP, and Tailscale-connected hosts.
          Those connections are made directly from your device to the remote host.
        </p>
        <p>
          Chuchu does not send your server list, terminal content, credentials, or backups to a
          Chuchu-operated server because Chuchu does not run one for the app itself.
        </p>

        <h2>3. Permissions</h2>
        <ul>
          <li><strong>Internet</strong>: Required to connect to remote hosts over SSH, Mosh, SFTP, or Tailscale.</li>
          <li><strong>Network state</strong>: Used to understand connectivity status while managing sessions.</li>
          <li><strong>Foreground service</strong>: Used to keep active terminal sessions running while the app is in the background.</li>
          <li><strong>Notifications</strong>: Used to show foreground-service session notifications on supported Android versions.</li>
        </ul>

        <h2>4. What Chuchu Does Not Do</h2>
        <ul>
          <li>No account creation.</li>
          <li>No advertising SDKs.</li>
          <li>No in-app analytics or profiling.</li>
          <li>No sale of personal data.</li>
          <li>No remote collection of terminal content, server credentials, or SSH keys by the Chuchu developer.</li>
        </ul>

        <h2>5. Third-Party Services</h2>
        <p>
          Chuchu can connect to third-party systems only when you direct it to do so, for example
          your SSH server, a Mosh server process started on that host, or a Tailscale-connected
          device. Those systems are controlled by you or by the operator of the remote host and are
          outside this privacy policy.
        </p>

        <h2>6. Data Retention and Control</h2>
        <p>
          Data stored by Chuchu remains on your device unless you export it yourself. You can delete
          saved hosts, keys, settings, and backup files at any time. Uninstalling the app removes
          app-local data according to Android's normal app data handling.
        </p>

        <h2>7. Security</h2>
        <p>
          Chuchu uses SSH-related trust data such as host fingerprints to help protect against host
          impersonation. However, no software can guarantee absolute security, and you remain
          responsible for the security of the remote systems you connect to and any backups you export.
        </p>

        <h2>8. Children</h2>
        <p>
          Chuchu is not intended for children. The app is a technical tool for remote system access
          and is not designed to knowingly collect personal information from children.
        </p>

        <h2>9. Changes to This Policy</h2>
        <p>
          This privacy policy may be updated if Chuchu's behavior changes, for example if a future
          release adds cloud sync, telemetry, or other data handling features. The current version of
          the policy will remain published at this URL.
        </p>

        <h2>10. Contact</h2>
        <p>
          For questions about this policy or the app, open an issue at
          <a href="https://github.com/jossephus/chuchu/issues">https://github.com/jossephus/chuchu/issues</a>.
        </p>
      </article>
    </div>
  </main>
</template>
