package com.jossephus.chuchu.data.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Downloads and installs the sherpa-onnx native runtime that backs Parakeet
 * dictation. The libraries are stripped from the APK at build time and pulled
 * on demand from the upstream sherpa-onnx release the first time the user
 * enables Parakeet, so the base install stays small.
 *
 * The download is the official sherpa-onnx AAR. We verify a pinned SHA-256
 * and extract only the two arm64-v8a `.so` files actually used by the Kotlin
 * bindings (libsherpa-onnx-jni.so + its NEEDED libonnxruntime.so).
 */
class ParakeetRuntimeStore(context: Context) {
    companion object {
        private const val TAG = "ParakeetRuntimeStore"
        private const val IO_BUFFER_SIZE = 256 * 1024
        private const val STATUS_UPDATE_MIN_INTERVAL_MS = 150L

        private const val RUNTIME_VERSION = "1.13.2"
        private const val RUNTIME_ABI = "arm64-v8a"
        private const val AAR_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$RUNTIME_VERSION/sherpa-onnx-$RUNTIME_VERSION.aar"
        private const val AAR_SHA256 =
            "aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245"

        /** Approximate AAR size for UI labelling. Actual content-length is verified by SHA. */
        const val DISPLAY_SIZE_LABEL: String = "54 MB"

        /**
         * Order matters for `System.load`: load `libonnxruntime.so` first so
         * the dynamic linker can satisfy `libsherpa-onnx-jni.so`'s NEEDED list.
         */
        private val LIB_LOAD_ORDER = listOf("libonnxruntime.so", "libsherpa-onnx-jni.so")

        @Volatile private var librariesLoaded: Boolean = false
    }

    sealed interface InstallStatus {
        data object Idle : InstallStatus
        data class Downloading(val progress: Float?) : InstallStatus
        data class Installing(val progress: Float?) : InstallStatus
    }

    private val appContext = context.applicationContext
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()
    private val _status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    val status: StateFlow<InstallStatus> = _status.asStateFlow()

    fun isInstalled(): Boolean {
        val dir = runtimeDir()
        return LIB_LOAD_ORDER.all { File(dir, it).let { f -> f.exists() && f.length() > 0L } }
    }

    fun runtimeDir(): File = File(filesRoot(), "$RUNTIME_VERSION/$RUNTIME_ABI")

    suspend fun download(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val partFile = File(cacheRoot(), "sherpa-onnx-$RUNTIME_VERSION.aar.part")
            val tempInstallDir = File(filesRoot(), "$RUNTIME_VERSION.tmp")
            runCatching {
                val finalInstallDir = runtimeDir()
                val installStartNs = SystemClock.elapsedRealtimeNanos()

                partFile.parentFile?.mkdirs()
                filesRoot().mkdirs()
                deleteRecursivelyIfExists(tempInstallDir)

                _status.value = InstallStatus.Downloading(progress = 0f)
                val downloadStartNs = SystemClock.elapsedRealtimeNanos()
                downloadToPartFile(partFile)
                logPhaseDuration("download", downloadStartNs)
                _status.value = InstallStatus.Installing(progress = 0f)

                val checksumStartNs = SystemClock.elapsedRealtimeNanos()
                verifySha256(partFile, AAR_SHA256)
                logPhaseDuration("checksum", checksumStartNs)

                val extractStartNs = SystemClock.elapsedRealtimeNanos()
                extractLibsFromAar(partFile, tempInstallDir)
                logPhaseDuration("extract", extractStartNs)

                validateLibs(tempInstallDir)

                deleteRecursivelyIfExists(finalInstallDir)
                finalInstallDir.parentFile?.mkdirs()
                if (!tempInstallDir.renameTo(finalInstallDir)) {
                    throw IllegalStateException("Failed to install voice runtime")
                }
                partFile.delete()
                logPhaseDuration("total", installStartNs)
                Unit
            }.onFailure {
                deleteRecursivelyIfExists(tempInstallDir)
                partFile.delete()
                if (it is java.util.concurrent.CancellationException) {
                    Log.i(TAG, "Install cancelled; cleaned temp files")
                }
            }.also {
                _downloadProgress.value = null
                _status.value = InstallStatus.Idle
            }
        }

    fun delete() {
        deleteRecursivelyIfExists(filesRoot())
        // Note: we do NOT reset `librariesLoaded`. Once a .so is dlopen'd into
        // the process it can't be unloaded; the user would need to restart the
        // app before a fresh install will be picked up. Tracking it as still
        // "loaded" prevents a later `ensureLoaded` from trying to System.load
        // a freshly downloaded copy on top of the live one.
    }

    /**
     * Pre-loads the sherpa-onnx native libraries and registers the install
     * directory with the app's classloader so that the sherpa-onnx Kotlin
     * companions' own `System.loadLibrary("sherpa-onnx-jni")` calls resolve
     * to the same files. Must be called before any `com.k2fsa.sherpa.onnx`
     * class is first referenced.
     */
    fun ensureLoaded() {
        if (librariesLoaded) return
        synchronized(ParakeetRuntimeStore::class.java) {
            if (librariesLoaded) return
            check(isInstalled()) { "Voice runtime is not installed" }
            val dir = runtimeDir()
            addNativeLibraryDir(appContext.classLoader, dir)
            for (libName in LIB_LOAD_ORDER) {
                System.load(File(dir, libName).absolutePath)
            }
            librariesLoaded = true
        }
    }

    /**
     * Append [dir] to the BaseDexClassLoader's native library search path so
     * `System.loadLibrary` (used by the sherpa-onnx Kotlin companions) can find
     * libraries we downloaded outside the APK. This is the same pattern used
     * by ReLinker / SoLoader and has been stable on Android since API 21.
     */
    private fun addNativeLibraryDir(classLoader: ClassLoader, dir: File) {
        runCatching {
            val pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
                .apply { isAccessible = true }
            val pathList = pathListField.get(classLoader)
                ?: throw IllegalStateException("BaseDexClassLoader.pathList is null")
            val addNativePath = pathList.javaClass
                .getDeclaredMethod("addNativePath", Collection::class.java)
                .apply { isAccessible = true }
            addNativePath.invoke(pathList, listOf(dir.absolutePath))
        }.onFailure {
            // Even if registration fails, the explicit System.load() calls below
            // will still pull both libraries into the process; the sherpa-onnx
            // companion's subsequent System.loadLibrary may then error on some
            // OEM ROMs. Log loudly so we notice in the field.
            Log.w(TAG, "Could not register native library dir with classloader", it)
        }
    }

    private suspend fun downloadToPartFile(partFile: File) {
        val connection = URL(AAR_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Failed to download voice runtime (${connection.responseCode})")
        }

        val contentLength = connection.contentLengthLong.takeIf { it > 0L }
        connection.inputStream.use { rawInput ->
            BufferedInputStream(rawInput, IO_BUFFER_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(partFile), IO_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    var totalRead = 0L
                    var lastProgressEmitMs = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        totalRead += read
                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastProgressEmitMs >= STATUS_UPDATE_MIN_INTERVAL_MS) {
                            val progress =
                                contentLength?.let { len ->
                                    (totalRead.toFloat() / len.toFloat()).coerceIn(0f, 1f)
                                }
                            _downloadProgress.value = progress
                            _status.value = InstallStatus.Downloading(progress)
                            lastProgressEmitMs = nowMs
                        }
                    }
                    val finalProgress =
                        contentLength?.let { len ->
                            (totalRead.toFloat() / len.toFloat()).coerceIn(0f, 1f)
                        }
                    _downloadProgress.value = finalProgress
                    _status.value = InstallStatus.Downloading(finalProgress)
                }
            }
        }
        connection.disconnect()
    }

    private suspend fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { rawInput ->
            BufferedInputStream(rawInput, IO_BUFFER_SIZE).use { input ->
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        val actual = digest.digest().joinToString(separator = "") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IllegalStateException("Voice runtime checksum mismatch")
        }
    }

    private suspend fun extractLibsFromAar(aarFile: File, outputDir: File) {
        outputDir.mkdirs()
        val wanted = LIB_LOAD_ORDER.map { "jni/$RUNTIME_ABI/$it" }.toSet()
        val seen = HashSet<String>()
        FileInputStream(aarFile).use { fis ->
            BufferedInputStream(fis, IO_BUFFER_SIZE).use { bis ->
                ZipInputStream(bis).use { zip ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        if (entry.name in wanted) {
                            val libName = entry.name.substringAfterLast('/')
                            val outFile = safeResolve(outputDir, libName)
                            BufferedOutputStream(FileOutputStream(outFile), IO_BUFFER_SIZE).use { out ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = zip.read(buffer)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                            seen += entry.name
                        }
                        zip.closeEntry()
                        if (seen.size == wanted.size) break
                    }
                }
            }
        }
        val missing = wanted - seen
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Voice runtime archive is missing: $missing")
        }
        _status.value = InstallStatus.Installing(progress = 1f)
    }

    private fun validateLibs(dir: File) {
        for (libName in LIB_LOAD_ORDER) {
            val f = File(dir, libName)
            if (!f.exists() || f.length() <= 0L) {
                throw IllegalStateException("Voice runtime is missing $libName")
            }
        }
    }

    private fun safeResolve(baseDir: File, entryName: String): File {
        val file = File(baseDir, entryName)
        val basePath = baseDir.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        if (!filePath.startsWith(basePath)) {
            throw IllegalStateException("Blocked unsafe archive entry")
        }
        return file
    }

    private fun deleteRecursivelyIfExists(file: File) {
        if (file.exists()) file.deleteRecursively()
    }

    private fun logPhaseDuration(phase: String, startNs: Long) {
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000
        Log.i(TAG, "phase=$phase duration_ms=$elapsedMs")
    }

    private fun cacheRoot(): File = File(appContext.cacheDir, "voice-runtime")

    private fun filesRoot(): File = File(appContext.filesDir, "voice-runtime")
}
