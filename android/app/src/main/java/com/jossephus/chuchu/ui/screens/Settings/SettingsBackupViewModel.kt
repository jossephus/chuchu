package com.jossephus.chuchu.ui.screens.Settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.R
import com.jossephus.chuchu.data.backup.BackupFormatException
import com.jossephus.chuchu.data.backup.BackupImportPlan
import com.jossephus.chuchu.data.backup.BackupPayload
import com.jossephus.chuchu.data.backup.ChuchuBackupRepository
import com.jossephus.chuchu.data.backup.InvalidBackupPassphraseException
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.service.backup.ChuchuBackupCodec
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val BACKUP_MIN_PASSPHRASE_LENGTH = 8

class SettingsBackupViewModel(
    application: Application,
) : AndroidViewModel(application) {
    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SettingsBackupViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return SettingsBackupViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }

    private val db = AppDatabase.getInstance(application)
    private val backupRepo = ChuchuBackupRepository(db)

    private val _keys = MutableStateFlow<List<SshKey>>(emptyList())
    val keys: StateFlow<List<SshKey>> = _keys.asStateFlow()

    private val _hosts = MutableStateFlow<List<HostProfile>>(emptyList())
    val hosts: StateFlow<List<HostProfile>> = _hosts.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _success = MutableStateFlow<String?>(null)
    val success: StateFlow<String?> = _success.asStateFlow()

    // Passphrase fields
    private val _exportPassphrase = MutableStateFlow("")
    val exportPassphrase: StateFlow<String> = _exportPassphrase.asStateFlow()

    private val _exportPassphraseConfirm = MutableStateFlow("")
    val exportPassphraseConfirm: StateFlow<String> = _exportPassphraseConfirm.asStateFlow()

    private val _importPassphrase = MutableStateFlow("")
    val importPassphrase: StateFlow<String> = _importPassphrase.asStateFlow()

    // Import preview state
    private val _importPlan = MutableStateFlow<BackupImportPlan?>(null)
    val importPlan: StateFlow<BackupImportPlan?> = _importPlan.asStateFlow()

    // Decrypted payload cached during import flow
    private var cachedPayload: BackupPayload? = null

    init {
        viewModelScope.launch {
            db.sshKeyDao().observeAll().collect { _keys.value = it }
        }
        viewModelScope.launch {
            db.hostProfileDao().observeAll().collect { _hosts.value = it }
        }
    }

    // ── Passphrase state setters ──────────────────────────────────────────────

    fun updateExportPassphrase(value: String) {
        _exportPassphrase.value = value
    }

    fun updateExportPassphraseConfirm(value: String) {
        _exportPassphraseConfirm.value = value
    }

    fun updateImportPassphrase(value: String) {
        _importPassphrase.value = value
    }

    fun exportPassphraseValid(): Boolean {
        val p = _exportPassphrase.value
        return p.length >= BACKUP_MIN_PASSPHRASE_LENGTH && p == _exportPassphraseConfirm.value
    }

    fun importPassphraseValid(): Boolean {
        return _importPassphrase.value.length >= 1
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Called from the Sheet composable after device auth succeeds and SAF returns
     * a writeable URI. Encrypts the backup in memory and writes to the URI.
     */
    fun performExport(uri: Uri) {
        if (_isBusy.value || !exportPassphraseValid()) return
        _isBusy.value = true
        viewModelScope.launch {
            _error.value = null
            _success.value = null
            val passphrase = _exportPassphrase.value.toCharArray()
            try {
                val encryptedBytes = withContext(Dispatchers.IO) {
                    backupRepo.createEncryptedBackup(passphrase)
                }
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(encryptedBytes)
                    } ?: throw BackupFormatException(
                        getApplication<Application>()
                            .getString(R.string.backup_error_open_destination)
                    )
                }
                _success.value =
                    getApplication<Application>().getString(R.string.backup_export_success)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _error.value =
                    getApplication<Application>().getString(R.string.backup_export_failed)
            } finally {
                passphrase.fill('\u0000')
                _isBusy.value = false
                clearExportPassphrase()
            }
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Called from the Sheet composable after device auth succeeds and SAF returns
     * a readable URI. Reads bytes, decrypts, and plans the import.
     */
    fun performImport(uri: Uri) {
        if (_isBusy.value || !importPassphraseValid()) return
        _isBusy.value = true
        viewModelScope.launch {
            _error.value = null
            _success.value = null
            _importPlan.value = null
            cachedPayload = null
            val passphrase = _importPassphrase.value.toCharArray()
            try {
                val bytes = withContext(Dispatchers.IO) {
                    readBackupBytes(uri)
                }
                val payload = withContext(Dispatchers.IO) {
                    backupRepo.readEncryptedBackup(bytes, passphrase)
                }
                cachedPayload = payload
                val plan = withContext(Dispatchers.IO) {
                    backupRepo.planImport(payload)
                }
                _importPassphrase.value = ""
                _importPlan.value = plan
            } catch (_: InvalidBackupPassphraseException) {
                _error.value =
                    getApplication<Application>().getString(R.string.backup_wrong_passphrase)
                clearImportPassphrase()
            } catch (_: BackupFormatException) {
                _error.value =
                    getApplication<Application>().getString(R.string.backup_invalid_file)
                clearImportPassphrase()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _error.value =
                    getApplication<Application>().getString(R.string.backup_read_failed)
                clearImportPassphrase()
            } finally {
                passphrase.fill('\u0000')
                _isBusy.value = false
            }
        }
    }

    /** Proceed with the cached import after user confirms the preview. */
    fun confirmImport() {
        if (_isBusy.value) return
        val payload = cachedPayload ?: return
        _isBusy.value = true
        viewModelScope.launch {
            _error.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    backupRepo.importPayload(payload)
                }
                val parts = mutableListOf<String>()
                val app = getApplication<Application>()
                if (result.importedKeys > 0) {
                    parts +=
                        app.resources.getQuantityString(
                            R.plurals.backup_result_keys_imported,
                            result.importedKeys,
                            result.importedKeys,
                        )
                }
                if (result.reusedKeys > 0) {
                    parts +=
                        app.resources.getQuantityString(
                            R.plurals.backup_result_keys_reused,
                            result.reusedKeys,
                            result.reusedKeys,
                        )
                }
                if (result.renamedKeys > 0) {
                    parts +=
                        app.resources.getQuantityString(
                            R.plurals.backup_result_keys_renamed,
                            result.renamedKeys,
                            result.renamedKeys,
                        )
                }
                if (result.importedHosts > 0) {
                    parts +=
                        app.resources.getQuantityString(
                            R.plurals.backup_result_hosts_imported,
                            result.importedHosts,
                            result.importedHosts,
                        )
                }
                if (result.renamedHosts > 0) {
                    parts +=
                        app.resources.getQuantityString(
                            R.plurals.backup_result_hosts_renamed,
                            result.renamedHosts,
                            result.renamedHosts,
                        )
                }
                _success.value =
                    app.getString(R.string.backup_import_complete, parts.joinToString(", "))
                clearImportState()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _error.value =
                    getApplication<Application>()
                        .getString(R.string.backup_import_failed_nothing_changed)
                clearImportState()
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Dismiss the import preview and clear cached data. */
    fun cancelImport() {
        clearImportState()
    }

    // ── Dismiss / clear ───────────────────────────────────────────────────────

    /** Clear everything when the sheet is dismissed or back is pressed. */
    fun dismissSheet() {
        clearAllState()
    }

    fun clearMessages() {
        _error.value = null
        _success.value = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun readBackupBytes(uri: Uri): ByteArray {
        val context = getApplication<Application>()
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                if (totalBytes > ChuchuBackupCodec.MAX_BACKUP_SIZE_BYTES) {
                    throw BackupFormatException(
                        getApplication<Application>()
                            .getString(R.string.backup_error_file_too_large)
                    )
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw BackupFormatException(
            getApplication<Application>().getString(R.string.backup_error_open_file)
        )
    }

    private fun clearExportPassphrase() {
        _exportPassphrase.value = ""
        _exportPassphraseConfirm.value = ""
    }

    private fun clearImportPassphrase() {
        _importPassphrase.value = ""
    }

    private fun clearImportState() {
        _importPassphrase.value = ""
        _importPlan.value = null
        cachedPayload = null
    }

    private fun clearAllState() {
        _exportPassphrase.value = ""
        _exportPassphraseConfirm.value = ""
        _importPassphrase.value = ""
        _importPlan.value = null
        cachedPayload = null
        _error.value = null
        _success.value = null
    }

    override fun onCleared() {
        clearAllState()
        super.onCleared()
    }
}
