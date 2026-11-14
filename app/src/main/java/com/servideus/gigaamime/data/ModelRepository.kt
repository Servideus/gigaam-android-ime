package com.servideus.gigaamime.data

import android.content.Context
import java.io.File
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

    fun getModelDir(model: GigaamModel): File = File(modelsRootDir, model.id)

    fun isModelDownloaded(model: GigaamModel): Boolean {
        val modelDir = getModelDir(model)
        return model.artifacts.all { artifact -> File(modelDir, artifact.fileName).isFile }
    }

    suspend fun deleteModel(model: GigaamModel) = withContext(Dispatchers.IO) {
        getModelDir(model).deleteRecursively()
    }

    /** Reports progress at most once per 0.1% so the UI thread is not flooded with updates. */
    suspend fun downloadModel(
        model: GigaamModel,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val modelDir = getModelDir(model)
        modelDir.deleteRecursively()
        if (!modelDir.mkdirs() && !modelDir.isDirectory) {
            throw IOException("Failed to create model directory: ${modelDir.absolutePath}")
        }

        val totalBytes = model.totalBytes
        var lastReportedPermille = -1L
        fun reportProgress(downloadedBytes: Long) {
            val permille = downloadedBytes * 1000L / totalBytes.coerceAtLeast(1L)
            if (permille != lastReportedPermille) {
                lastReportedPermille = permille
                onProgress(downloadedBytes, totalBytes)
            }
        }

        var completedBytes = 0L
        reportProgress(0L)
        for (artifact in model.artifacts) {
            downloadArtifact(artifact, File(modelDir, artifact.fileName)) { artifactBytes ->
                reportProgress(completedBytes + artifactBytes)
            }
            completedBytes += artifact.sizeBytes
            reportProgress(completedBytes)
        }
    }

    /**
     * Streams the file to a `.part` file while hashing it, and moves it into place only after
     * the size and SHA-256 match. A failed download never leaves a file under the final name.
     */
    private fun downloadArtifact(
        artifact: ModelArtifact,
        targetFile: File,
        onProgress: (downloadedBytes: Long) -> Unit,
    ) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        val request = Request.Builder()
            .url(artifact.url)
            .header("User-Agent", "GigaAM-IME/0.1 (Android)")
            .header("Accept", "*/*")
            .build()

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalRead = 0L
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Download failed for ${artifact.fileName}: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body for ${artifact.fileName}")
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            totalRead += read
                            onProgress(totalRead)
                        }
                    }
                }
            }

            if (totalRead != artifact.sizeBytes) {
                throw IOException(
                    "Size mismatch for ${artifact.fileName}: expected ${artifact.sizeBytes}, got $totalRead",
                )
            }
            val actualSha = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
            if (!actualSha.equals(artifact.sha256, ignoreCase = true)) {
                throw IOException(
                    "SHA-256 mismatch for ${artifact.fileName}: expected ${artifact.sha256}, got $actualSha",
                )
            }
            if (!tempFile.renameTo(targetFile)) {
                throw IOException("Failed to move ${artifact.fileName} into the model directory")
            }
        } catch (error: Exception) {
            tempFile.delete()
            throw error
        }
    }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }
}
