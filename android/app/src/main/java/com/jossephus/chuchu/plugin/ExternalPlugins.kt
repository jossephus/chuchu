package com.jossephus.chuchu.plugin

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jossephus.chuchu.plugin.api.ChuchuPlugin
import dalvik.system.PathClassLoader
import java.io.File
import java.security.MessageDigest

/** A plugin APK found on the device, whether or not the user enabled it. */
data class DiscoveredPlugin(
    val descriptor: PluginDescriptor,
    val entryClass: String?,
    /** SHA-256 of the APK's signing certificate, hex; null if it couldn't be read. */
    val certSha256: String?,
    /** Why the manifest can't be loaded, or null when it's well-formed. */
    val manifestProblem: String?,
)

enum class ExternalPluginState {
    /** Installed but the user hasn't enabled it. */
    Disabled,

    /** Enabled; loads at startup. */
    Enabled,

    /** Enabled for a different signing certificate: an update from someone else, or a re-sign. */
    CertificateChanged,

    /** Chuchu died shortly after loading it last time; the user must re-enable it. */
    Quarantined,

    /** Manifest is missing required meta-data. */
    Invalid,
}

/**
 * The state a discovered plugin is in, from what the user approved ([enabledCert], the
 * certificate pinned at consent) and whether it was quarantined after a crash.
 */
internal fun externalPluginState(
    plugin: DiscoveredPlugin,
    enabledCert: String?,
    quarantined: Boolean,
): ExternalPluginState =
    when {
        plugin.manifestProblem != null || plugin.certSha256 == null -> ExternalPluginState.Invalid
        quarantined -> ExternalPluginState.Quarantined
        enabledCert == null -> ExternalPluginState.Disabled
        enabledCert != plugin.certSha256 -> ExternalPluginState.CertificateChanged
        else -> ExternalPluginState.Enabled
    }

/**
 * Separately installed plugin APKs: discovery, user consent (pinned to the signing
 * certificate), crash quarantine, and loading into chuchu's process.
 *
 * Plugins run in-process with chuchu's privileges; the consent dialog says so. Nothing here
 * sandboxes them. What it does guarantee: a plugin only loads after the user enabled that
 * exact signer, and a plugin that takes chuchu down at startup doesn't do it twice.
 */
class ExternalPlugins(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    /** Plugin APKs currently installed, by intent-filter discovery. */
    fun discover(): List<DiscoveredPlugin> {
        val pm = context.packageManager
        val packages =
            queryPluginActivities(pm).mapTo(LinkedHashSet()) { it.activityInfo.packageName }
        return packages.mapNotNull { pkg -> runCatching { describe(pm, pkg) }.getOrNull() }
    }

    fun state(plugin: DiscoveredPlugin): ExternalPluginState =
        externalPluginState(plugin, enabledCert(plugin.descriptor.id), isQuarantined(plugin.descriptor.id))

    /** Records consent for this exact signer. Takes effect after a restart. */
    fun enable(plugin: DiscoveredPlugin) {
        val cert = requireNotNull(plugin.certSha256) { "plugin has no readable signing certificate" }
        prefs.edit()
            .putString(KEY_ENABLED + plugin.descriptor.id, cert)
            .remove(KEY_QUARANTINED + plugin.descriptor.id)
            .apply()
    }

    fun disable(pluginId: String) {
        prefs.edit().remove(KEY_ENABLED + pluginId).apply()
    }

    /**
     * Entries for every enabled, correctly signed plugin, and arms the crash quarantine:
     * ids are recorded as "launching" until [QUARANTINE_WINDOW_MS] pass without chuchu
     * dying. Call once at startup, on the main thread.
     */
    fun startupEntries(): List<PluginEntry> {
        quarantineCrashedLaunch()
        val entries = discover().filter { state(it) == ExternalPluginState.Enabled }.mapNotNull(::entryFor)
        if (entries.isNotEmpty()) {
            prefs.edit().putStringSet(KEY_LAUNCHING, entries.mapTo(HashSet()) { it.descriptor.id }).commit()
            Handler(Looper.getMainLooper()).postDelayed(
                { prefs.edit().remove(KEY_LAUNCHING).apply() },
                QUARANTINE_WINDOW_MS,
            )
        }
        return entries
    }

    private fun enabledCert(pluginId: String): String? = prefs.getString(KEY_ENABLED + pluginId, null)

    private fun isQuarantined(pluginId: String): Boolean = prefs.getBoolean(KEY_QUARANTINED + pluginId, false)

    // Still marked "launching" means the previous process died inside the window, most
    // likely because of a plugin. We can't tell which one, so all of them are quarantined.
    private fun quarantineCrashedLaunch() {
        val crashed = prefs.getStringSet(KEY_LAUNCHING, null).orEmpty()
        if (crashed.isEmpty()) return
        val editor = prefs.edit().remove(KEY_LAUNCHING)
        crashed.forEach { editor.putBoolean(KEY_QUARANTINED + it, true) }
        editor.commit()
    }

    private fun entryFor(plugin: DiscoveredPlugin): PluginEntry? {
        val pkg = plugin.descriptor.packageName ?: return null
        val entryClass = plugin.entryClass ?: return null
        val pluginContext =
            runCatching {
                context.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            }.getOrNull() ?: return null
        return PluginEntry(plugin.descriptor, pluginContext) { instantiate(pluginContext, entryClass) }
    }

    private fun instantiate(pluginContext: Context, entryClass: String): ChuchuPlugin {
        val info = pluginContext.applicationInfo
        val dexPath = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).joinToString(File.pathSeparator)
        // Parent is chuchu's own loader, so plugin-api, Compose, coroutines and Kotlin resolve
        // to chuchu's copies: one Compose runtime in the process, and shared API types.
        val loader = PathClassLoader(dexPath, info.nativeLibraryDir, ChuchuPlugin::class.java.classLoader)
        return loader.loadClass(entryClass).getDeclaredConstructor().newInstance() as ChuchuPlugin
    }

    private fun describe(pm: PackageManager, pkg: String): DiscoveredPlugin {
        val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
        val meta = appInfo.metaData
        val id = meta?.getString(META_ID)
        val entry = meta?.getString(META_ENTRY)
        val apiVersion = meta?.getInt(META_API_VERSION, -1) ?: -1
        val problem =
            when {
                id.isNullOrBlank() -> "missing $META_ID"
                !ID_PATTERN.matches(id) -> "$META_ID must be lowercase letters, digits and underscores"
                entry.isNullOrBlank() -> "missing $META_ENTRY"
                apiVersion < 0 -> "missing $META_API_VERSION"
                else -> null
            }
        val packageInfo = packageInfo(pm, pkg)
        return DiscoveredPlugin(
            descriptor =
                PluginDescriptor(
                    id = id?.takeIf { it.isNotBlank() } ?: pkg,
                    name = appInfo.loadLabel(pm).toString(),
                    version = packageInfo.versionName ?: "?",
                    apiVersion = apiVersion,
                    source = PluginSource.External,
                    packageName = pkg,
                ),
            entryClass = entry,
            certSha256 = signingCertSha256(packageInfo),
            manifestProblem = problem,
        )
    }

    @Suppress("DEPRECATION")
    private fun queryPluginActivities(pm: PackageManager) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(Intent(ACTION_PLUGIN), PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(Intent(ACTION_PLUGIN), 0)
        }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun signingCertSha256(info: PackageInfo): String? {
        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signing = info.signingInfo ?: return null
                // Multiple signers are unusual; refusing them keeps "the pinned cert" well defined.
                if (signing.hasMultipleSigners()) return null
                signing.signingCertificateHistory
            } else {
                info.signatures
            }
        // The newest certificate in the rotation history is the one that signed this APK.
        val cert = signatures?.lastOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val ACTION_PLUGIN = "com.jossephus.chuchu.PLUGIN"
        const val META_ID = "chuchu.plugin.id"
        const val META_ENTRY = "chuchu.plugin.entry"
        const val META_API_VERSION = "chuchu.plugin.apiVersion"

        // Matches the settings/prefs file naming, where ids become file names.
        private val ID_PATTERN = Regex("^[a-z0-9_]{1,64}$")
        private const val PREFS_NAME = "external_plugins"
        private const val KEY_ENABLED = "enabled."
        private const val KEY_QUARANTINED = "quarantined."
        private const val KEY_LAUNCHING = "launching"

        // Long enough to cover plugin registration and the first screens being composed.
        private const val QUARANTINE_WINDOW_MS = 15_000L

        @Volatile private var instance: ExternalPlugins? = null

        fun getInstance(context: Context): ExternalPlugins =
            instance ?: synchronized(this) {
                instance ?: ExternalPlugins(
                    context.applicationContext,
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
