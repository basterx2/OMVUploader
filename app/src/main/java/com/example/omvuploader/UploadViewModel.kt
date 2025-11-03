package com.example.omvuploader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UploadViewModel(application: Application) : AndroidViewModel(application) {

    private val credentialsManager = CredentialsManager(application)
    private var smbManager: SMBManager? = null

    private val _uploadProgress = MutableLiveData<Int>()
    val uploadProgress: LiveData<Int> = _uploadProgress

    private val _uploadStatus = MutableLiveData<String>()
    val uploadStatus: LiveData<String> = _uploadStatus

    private val _isUploading = MutableLiveData<Boolean>()
    val isUploading: LiveData<Boolean> = _isUploading

    private val _connectionStatus = MutableLiveData<String>()
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _uploadComplete = MutableLiveData<Boolean>()
    val uploadComplete: LiveData<Boolean> = _uploadComplete

    init {
        _uploadProgress.value = 0
        _uploadStatus.value = "Esperando archivos..."
        _isUploading.value = false
        _connectionStatus.value = "Desconectado"
    }

    fun hasCredentials(): Boolean {
        return credentialsManager.hasCredentials()
    }

    fun saveCredentials(server: String, username: String, password: String, shareName: String = "RaspberryHDD") {
        credentialsManager.saveCredentials(server, username, password, shareName)
        // No inicializamos aquí, se hace en testConnection
    }

    private suspend fun initializeSMBManager(): SMBManager? {
        return withContext(Dispatchers.IO) {
            try {
                val credentials = credentialsManager.getCredentials()
                if (credentials != null) {
                    val manager = SMBManager(
                        credentials.server,
                        credentials.username,
                        credentials.password,
                        credentials.shareName ?: "RaspberryHDD"
                    )
                    manager.initialize()
                    manager
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _connectionStatus.value = "Conectando..."
            try {
                smbManager = initializeSMBManager()

                val result = withContext(Dispatchers.IO) {
                    smbManager?.testConnection() ?: false
                }

                _connectionStatus.value = if (result) {
                    "Conectado: ${credentialsManager.getCredentials()?.server}"
                } else {
                    "Error de conexión"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _connectionStatus.value = "Error: ${e.message}"
            }
        }
    }

    fun uploadFiles(context: Context, uris: List<Uri>, dateFolder: String) {
        viewModelScope.launch {
            _isUploading.value = true
            _uploadProgress.value = 0
            _uploadStatus.value = "Iniciando subida..."

            try {
                if (smbManager == null) {
                    smbManager = initializeSMBManager()
                }

                if (smbManager == null) {
                    _uploadStatus.value = "Error: No se pudo conectar al servidor"
                    _uploadComplete.value = false
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val totalFiles = uris.size
                    uris.forEachIndexed { index, uri ->
                        val fileName = getFileName(context, uri)
                        _uploadStatus.postValue("Subiendo: $fileName (${index + 1}/$totalFiles)")

                        val inputStream = context.contentResolver.openInputStream(uri)
                        inputStream?.use { stream ->
                            smbManager?.uploadFile(
                                stream,
                                fileName,
                                dateFolder
                            )
                        }

                        val progress = ((index + 1) * 100) / totalFiles
                        _uploadProgress.postValue(progress)
                    }
                }

                _uploadStatus.value = "¡Subida completada!"
                _uploadComplete.value = true
                _uploadProgress.value = 100

            } catch (e: Exception) {
                e.printStackTrace()
                _uploadStatus.value = "Error: ${e.message}"
                _uploadComplete.value = false
            } finally {
                _isUploading.value = false
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var fileName = "file_${System.currentTimeMillis()}"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }
}