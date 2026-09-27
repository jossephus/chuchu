# hello-plugin: a minimal chuchu plugin

A separately installed APK that chuchu loads into its own process once the user enables it
(Settings › plugins). Copy this module to start a plugin.

What it shows:

- `registerCommand`: chuchu key + `h`, or the `hello` accessory button, types a greeting.
- `registerSessionView`: a banner above the terminal, decorating (not replacing) it.
- `registerSettings`: the greeting text, rendered by chuchu under Settings › plugins.
- Plugin resources (`R.string.banner`) resolving inside chuchu's UI.

## The contract

`AndroidManifest.xml`:

- `chuchu.plugin.id`: stable id, lowercase letters, digits and `_`. It names the plugin's
  settings storage, so never change it.
- `chuchu.plugin.entry`: your `ChuchuPlugin` class (public no-arg constructor).
- `chuchu.plugin.apiVersion`: the `PluginApi.VERSION` you compiled against.
- An exported activity with the `com.jossephus.chuchu.PLUGIN` action and **no LAUNCHER
  category**. That's how chuchu finds the plugin, and it keeps it out of the app drawer.

`build.gradle.kts` (this in-repo example uses `project(":plugin-api")`; a standalone plugin
gets the API from JitPack):

```kotlin
repositories { maven("https://jitpack.io") }
dependencies {
    compileOnly("com.github.jossephus.chuchu:plugin-api:<chuchu release tag>")
}
```


- `plugin-api` and Compose (runtime/ui/foundation) are `compileOnly`: chuchu provides them.
- kotlin-stdlib is excluded from the runtime classpath for the same reason.
- Anything else you need (other libraries, native `.so`s) you bundle as usual.

## Trust and lifecycle

- Plugins are off until the user enables them. Consent pins your signing certificate; an
  update signed with a different key stays off until the user approves it again.
- Enabling, disabling or updating takes effect after chuchu restarts.
- If chuchu dies within 15 s of loading plugins, they're quarantined until re-enabled.
- A plugin that throws from `register`, a command or its `scope` is disabled for that run.
  Exceptions thrown during composition can't be isolated and crash chuchu, so keep
  composables simple and do work in `host.scope`.

## Build and try

```sh
./gradlew :examples:hello-plugin:assembleDebug
adb install -r examples/hello-plugin/build/outputs/apk/debug/hello-plugin-debug.apk
```

Then in chuchu: Settings › plugins › enable "Hello" › restart.
