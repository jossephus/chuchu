package com.jossephus.chuchu.ui.screens.Keys

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jossephus.chuchu.data.db.AppDatabase
import com.jossephus.chuchu.data.repository.HostRepository
import com.jossephus.chuchu.data.repository.ImportResult
import com.jossephus.chuchu.data.repository.RenameResult
import com.jossephus.chuchu.data.repository.SshKeyRepository
import com.jossephus.chuchu.model.SshKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class KeysViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(KeysViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return KeysViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }

    private val db = AppDatabase.getInstance(application)
    private val sshKeyRepository = SshKeyRepository(db.sshKeyDao())
    private val hostRepository = HostRepository(db.hostProfileDao())

    val state: StateFlow<KeysUiState> =
        combine(sshKeyRepository.observeAll(), hostRepository.observeKeyUsageCounts()) { keys, usage
                ->
                KeysUiState(
                    keys =
                        keys.map { key ->
                            KeyListItem(key = key, usedByServers = usage[key.id] ?: 0)
                        }
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = KeysUiState(),
            )

    private val _events = MutableSharedFlow<KeysEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun generate(nameHint: String, passphrase: String = "") {
        viewModelScope.launch {
            val key = sshKeyRepository.generate(nameHint, passphrase)
            _events.emit(KeysEvent.Generated(key.name))
        }
    }

    fun import(nameHint: String, privateKeyPem: String) {
        viewModelScope.launch {
            val event =
                when (val result = sshKeyRepository.import(nameHint, privateKeyPem)) {
                    is ImportResult.Success ->
                        KeysEvent.Imported(result.key.name, result.publicKeyDerived)
                    ImportResult.Blank -> KeysEvent.ImportFailed("Paste a private key first")
                    ImportResult.NotAPrivateKey ->
                        KeysEvent.ImportFailed("That doesn't look like a private key")
                }
            _events.emit(event)
        }
    }

    fun rename(id: Long, newName: String) {
        viewModelScope.launch {
            val event =
                when (sshKeyRepository.rename(id, newName)) {
                    RenameResult.Success -> KeysEvent.Renamed
                    RenameResult.Blank -> KeysEvent.RenameFailed("Name can't be empty")
                    RenameResult.NameTaken ->
                        KeysEvent.RenameFailed("A key named \"${newName.trim()}\" already exists")
                }
            _events.emit(event)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            hostRepository.clearKeyReference(id)
            sshKeyRepository.deleteById(id)
            _events.emit(KeysEvent.Deleted)
        }
    }
}

data class KeysUiState(val keys: List<KeyListItem> = emptyList())

data class KeyListItem(val key: SshKey, val usedByServers: Int)

sealed interface KeysEvent {
    data class Generated(val name: String) : KeysEvent

    data class Imported(val name: String, val publicKeyDerived: Boolean) : KeysEvent

    data class ImportFailed(val message: String) : KeysEvent

    data object Renamed : KeysEvent

    data class RenameFailed(val message: String) : KeysEvent

    data object Deleted : KeysEvent
}
