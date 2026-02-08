package com.servideus.gigaamime.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ModelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .callTimeout(30, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    val modelsRootDir: File by lazy {
        File(appContext.filesDir, "models").apply { mkdirs() }
    }

    fun getModelDir(model: GigaamModel): File {
        return File(modelsRootDir, model.directoryName)
    }

    fun isModelDownloaded(model: GigaamModel): Boolean {
        val modelDir = getModelDir(model)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return false
        }
        return model.artifacts.all { artifact ->
            File(modelDir, artifact.fileName).isFile
        }
    }

    suspend fun deleteModel(model: GigaamModel) = withContext(Dispatchers.IO) {
        getModelDir(model).deleteRecursively()
    }

    suspend fun downloadModel(
        model: GigaamModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val modelDir = getModelDir(model)
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        if (!modelDir.mkdirs() && !modelDir.exists()) {
            throw IOException("Failed to create model directory: ${modelDir.absolutePath}")
        }

        var downloadedTotal = 0L
        onProgress(0L, model.totalBytes)

        for (artifact in model.artifacts) {
            val targetFile = File(modelDir, artifact.fileName)
            downloadArtifact(artifact, targetFile) { currentBytes ->
                onProgress(downloadedTotal + currentBytes, model.totalBytes)
            }
            validateArtifact(targetFile, artifact)
            downloadedTotal += artifact.sizeBytes
            onProgress(downloadedTotal, model.totalBytes)
        }
    }

    private fun downloadArtifact(
        artifact: ModelArtifact,
        targetFile: File,
        onProgress: (downloadedBytes: Long) -> Unit,
    ) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val request = Request.Builder()
            .url(artifact.url)
            .header("User-Agent", "GigaAM-IME/0.1 (Android)")
            .header("Accept", "*/*")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed for ${artifact.fileName}: HTTP ${response.code}")
                }
                val responseBody = response.body ?: throw IOException("Empty response body for ${artifact.fileName}")
                FileOutputStream(tempFile).use { outputStream ->
                    responseBody.byteStream().use { inputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var totalRead = 0L
                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read < 0) {
                                break
                            }
                            if (read == 0) {
                                continue
                            }
                            outputStream.write(buffer, 0, read)
                            totalRead += read
                            onProgress(totalRead)
                        }
                        if (totalRead == 0L) {
                            throw IOException("Downloaded 0 bytes for ${artifact.fileName}")
                        }
                    }
                }
            }
        } catch (error: Exception) {
            tempFile.delete()
            throw error
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun validateArtifact(file: File, artifact: ModelArtifact) {
        if (!file.exists()) {
            throw IOException("File missing after download: ${artifact.fileName}")
        }
        if (file.length() != artifact.sizeBytes) {
            throw IOException(
                "Size mismatch for ${artifact.fileName}: expected ${artifact.sizeBytes}, got ${file.length()}",
            )
        }
        val actualSha = sha256(file)
        if (!actualSha.equals(artifact.sha256, ignoreCase = true)) {
            throw IOException(
                "SHA-256 mismatch for ${artifact.fileName}: expected ${artifact.sha256}, got $actualSha",
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
