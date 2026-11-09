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

data class DiscoveredPlugin(
    val descriptor: PluginDescriptor,
    val entryClass: String?,
    val certSha256: String?,
    val manifestProblem: String?,
)

enum class ExternalPluginState {
    Disabled,

    Enabled,

    CertificateChanged,

    Quarantined,

    Invalid,
}

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

class ExternalPlugins(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    fun discover(): List<DiscoveredPlugin> {
        val pm = context.packageManager
        val packages =
            queryPluginActivities(pm).mapTo(LinkedHashSet()) { it.activityInfo.packageName }
        return packages.mapNotNull { pkg -> runCatching { describe(pm, pkg) }.getOrNull() }
    }

    fun state(plugin: DiscoveredPlugin): ExternalPluginState =
        externalPluginState(plugin, enabledCert(plugin.descriptor.id), isQuarantined(plugin.descriptor.id))

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

    // Still "launching" means the last process died inside the window; we cannot tell which plugin did it.
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
        val hostLoader = requireNotNull(ChuchuPlugin::class.java.classLoader)
        val loader = PathClassLoader(dexPath, info.nativeLibraryDir, SharedClassLoader(hostLoader))
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
                if (signing.hasMultipleSigners()) return null
                signing.signingCertificateHistory
            } else {
                info.signatures
            }
        val cert = signatures?.lastOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // Exposes only the packages chuchu shares and keeps under R8 plus the framework; anything
    // else resolves from the plugin APK, since chuchu's copy may be stripped or a different version.
    private class SharedClassLoader(private val host: ClassLoader) : ClassLoader(FRAMEWORK_LOADER) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (SHARED_PACKAGES.any(name::startsWith)) {
                try {
                    return host.loadClass(name)
                } catch (_: ClassNotFoundException) {
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    companion object {
        const val ACTION_PLUGIN = "com.jossephus.chuchu.PLUGIN"
        const val META_ID = "chuchu.plugin.id"
        const val META_ENTRY = "chuchu.plugin.entry"
        const val META_API_VERSION = "chuchu.plugin.apiVersion"

        // Must match plugin-api/consumer-rules.pro.
        private val SHARED_PACKAGES =
            listOf(
                "com.jossephus.chuchu.plugin.api.",
                "androidx.compose.runtime.",
                "androidx.compose.ui.",
                "androidx.compose.foundation.",
                "androidx.compose.animation.",
                "kotlin.",
                "kotlinx.coroutines.",
            )

        private val FRAMEWORK_LOADER: ClassLoader by lazy { requireNotNull(String::class.java.classLoader) }

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
