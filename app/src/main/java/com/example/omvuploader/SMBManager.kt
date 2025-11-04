package com.example.omvuploader

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream
import java.util.*

class SMBManager(
    private val serverIp: String,
    private val username: String,
    private val password: String,
    private val shareName: String = "RaspberryHDD"
) {
    // JCIFS-NG maneja espacios automáticamente en las rutas SMB
    private val folderPath = ""
    private val basePath = "smb://$serverIp/$shareName/$folderPath"

    private var context: CIFSContext? = null
    private var isInitialized = false

    init {
        println("SMBManager: Inicializando con basePath: $basePath")
    }

    suspend fun initialize() {
        if (!isInitialized) {
            initializeContext()
            isInitialized = true
        }
    }

    private fun initializeContext() {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.connTimeout", "30000")
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.netbios.hostname", "ANDROID")
        }

        try {
            val config = PropertyConfiguration(properties)
            val baseContext = BaseContext(config)
            val auth = NtlmPasswordAuthenticator(null, username, password)
            context = baseContext.withCredentials(auth)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    fun testConnection(): Boolean {
        return try {
            if (context == null) {
                initializeContext()
            }
            println("SMBManager: Probando conexión a: $basePath")
            val smbFile = SmbFile(basePath, context)
            val exists = smbFile.exists()
            println("SMBManager: Resultado de conexión: $exists")
            exists
        } catch (e: Exception) {
            e.printStackTrace()
            println("SMBManager: Error en testConnection: ${e.message}")
            println("SMBManager: Stack trace: ${e.stackTraceToString()}")
            false
        }
    }

    fun uploadFile(inputStream: InputStream, fileName: String, dateFolder: String) {
        try {
            if (context == null) {
                initializeContext()
            }

            // Crear carpeta con fecha si no existe
            val folderPath = "$basePath$dateFolder/"
            val folder = SmbFile(folderPath, context)
            if (!folder.exists()) {
                folder.mkdirs()
            }

            // Subir archivo
            val filePath = "$folderPath$fileName"
            val smbFile = SmbFile(filePath, context)

            smbFile.outputStream.use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    fun listFiles(path: String = ""): List<FileItem> {
        return try {
            if (context == null) {
                initializeContext()
            }

            val fullPath = if (path.isEmpty()) basePath else "$basePath$path/"
            println("SMBManager: Intentando listar archivos en: $fullPath")

            val smbFile = SmbFile(fullPath, context)

            if (!smbFile.exists()) {
                println("SMBManager: La ruta no existe: $fullPath")
                return emptyList()
            }

            if (!smbFile.isDirectory) {
                println("SMBManager: La ruta no es un directorio: $fullPath")
                return emptyList()
            }

            val files = smbFile.listFiles()
            println("SMBManager: Se encontraron ${files?.size ?: 0} archivos")

            files?.mapNotNull { file ->
                try {
                    FileItem(
                        name = file.name.trimEnd('/'),
                        isDirectory = file.isDirectory,
                        size = if (file.isDirectory) 0 else file.length(),
                        path = file.path
                    )
                } catch (e: Exception) {
                    println("SMBManager: Error al procesar archivo ${file.name}: ${e.message}")
                    null
                }
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            println("SMBManager: Error al listar archivos: ${e.message}")
            println("SMBManager: Stack trace: ${e.stackTraceToString()}")
            emptyList()
        }
    }

    fun downloadFile(filePath: String): ByteArray? {
        return try {
            if (context == null) {
                initializeContext()
            }

            val smbFile = SmbFile(filePath, context)
            smbFile.inputStream.use { input ->
                input.readBytes()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun downloadFileThumbnail(filePath: String, maxSize: Int = 500000): ByteArray? {
        return try {
            if (context == null) {
                initializeContext()
            }

            val smbFile = SmbFile(filePath, context)
            val fileSize = smbFile.length()

            // Si el archivo es muy grande, descargar una porción razonable
            if (fileSize > 20_000_000) { // 20MB
                // Para archivos muy grandes, descargar 500KB
                smbFile.inputStream.use { input ->
                    val buffer = ByteArray(500000)
                    val bytesRead = input.read(buffer, 0, 500000)
                    if (bytesRead > 0) buffer.copyOf(bytesRead) else null
                }
            } else {
                // Para archivos menores a 20MB, descargar completo
                smbFile.inputStream.use { input ->
                    input.readBytes()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("SMBManager: Error descargando thumbnail: ${e.message}")
            null
        }
    }

    fun createFolder(currentPath: String, folderName: String): Boolean {
        return try {
            if (context == null) {
                initializeContext()
            }

            val fullPath = if (currentPath.isEmpty()) {
                "$basePath$folderName/"
            } else {
                "$basePath$currentPath/$folderName/"
            }

            println("SMBManager: Creando carpeta en: $fullPath")
            val smbFile = SmbFile(fullPath, context)

            if (!smbFile.exists()) {
                smbFile.mkdir()
                println("SMBManager: Carpeta creada exitosamente")
                true
            } else {
                println("SMBManager: La carpeta ya existe")
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("SMBManager: Error al crear carpeta: ${e.message}")
            false
        }
    }
}

data class FileItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val path: String
)