package com.servideus.gigaamime.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import com.servideus.gigaamime.R
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a model download stands, read from the system DownloadManager so it survives process death. */
sealed interface DownloadState {
    data object None : DownloadState
    data class Running(val downloadedBytes: Long, val totalBytes: Long, val waitingForNetwork: Boolean) : DownloadState
    data class Failed(val reason: Int) : DownloadState
    data object Complete : DownloadState
}

/** A downloaded file failed the size or SHA-256 check; the message keeps the details for logs. */
class CorruptModelFileException(message: String) : IOException(message)

class NotEnoughSpaceException(val requiredBytes: Long) : IOException("Not enough free space: need $requiredBytes bytes")

class ModelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val storageManager = appContext.getSystemService(StorageManager::class.java)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val modelsRootDir: File by lazy {
        File(appContext.filesDir, "models").apply { mkdirs() }
    }

    fun getModelDir(model: GigaamModel): File = File(modelsRootDir, model.id)

    fun isModelDownloaded(model: GigaamModel): Boolean {
        val modelDir = getModelDir(model)
        return model.artifacts.all { artifact -> File(modelDir, artifact.fileName).isFile }
    }

    suspend fun deleteModel(model: GigaamModel) = withContext(Dispatchers.IO) {
        cancelDownload()
        getModelDir(model).deleteRecursively()
    }

    /** Frees folders of models this version no longer offers (the earlier e2e-CTC builds). */
    suspend fun deleteRetiredModels() = withContext(Dispatchers.IO) {
        val knownIds = GigaamModel.entries.map { it.id }.toSet()
        modelsRootDir.listFiles()
            ?.filter { it.isDirectory && it.name !in knownIds }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * Queues the missing files in the system DownloadManager. It keeps going after the app closes,
     * waits for the network and resumes. Files arrive in external app storage; [finalizeDownload]
     * verifies them and moves them in. Already installed files are kept, so a retry fetches only the rest.
     */
    suspend fun startDownload(model: GigaamModel) = withContext(Dispatchers.IO) {
        cancelDownload()
        val modelDir = getModelDir(model)
        modelDir.mkdirs()
        val missing = model.artifacts.filterNot { File(modelDir, it.fileName).isFile }
        if (missing.isEmpty()) return@withContext

        // Staging and the verified copy can share one partition, so ask for both.
        val required = 2 * missing.sumOf { it.sizeBytes } + SPACE_MARGIN_BYTES
        val staging = stagingDir()
        if (allocatableBytes(appContext.filesDir) < required || allocatableBytes(staging) < required) {
            throw NotEnoughSpaceException(required)
        }

        val ids = missing.map { artifact ->
            File(staging, artifact.fileName).delete()
            downloadManager.enqueue(
                DownloadManager.Request(Uri.parse(artifact.url))
                    .setTitle(appContext.getString(R.string.download_notification_title))
                    .setDescription(artifact.fileName)
                    .setDestinationInExternalFilesDir(appContext, STAGING_DIR, artifact.fileName)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE),
            )
        }
        prefs.edit().putString(KEY_DOWNLOAD_IDS, ids.joinToString(",")).apply()
    }

    fun downloadState(model: GigaamModel): DownloadState {
        val ids = downloadIds()
        if (ids.isEmpty()) return DownloadState.None

        var downloaded = 0L
        var waiting = false
        var successful = 0
        downloadManager.query(DownloadManager.Query().setFilterById(*ids)).use { cursor ->
            val statusCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reasonCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val bytesCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            if (cursor.count < ids.size) return DownloadState.Failed(REASON_REMOVED)
            while (cursor.moveToNext()) {
                when (cursor.getInt(statusCol)) {
                    DownloadManager.STATUS_FAILED -> return DownloadState.Failed(cursor.getInt(reasonCol))
                    DownloadManager.STATUS_SUCCESSFUL -> successful++
                    DownloadManager.STATUS_PAUSED -> waiting = true
                }
                downloaded += cursor.getLong(bytesCol).coerceAtLeast(0L)
            }
        }
        if (successful == ids.size) return DownloadState.Complete

        val modelDir = getModelDir(model)
        val installed = model.artifacts.filter { File(modelDir, it.fileName).isFile }.sumOf { it.sizeBytes }
        return DownloadState.Running(installed + downloaded, model.totalBytes, waiting)
    }

    /**
     * Copies each finished file into internal storage while hashing it, and keeps it only when
     * size and SHA-256 match. Safe to call again: installed files are skipped.
     */
    suspend fun finalizeDownload(model: GigaamModel) = finalizeLock.withLock {
        withContext(Dispatchers.IO) {
            if (downloadState(model) != DownloadState.Complete) return@withContext
            val modelDir = getModelDir(model).apply { mkdirs() }
            val staging = stagingDir()
            try {
                for (artifact in model.artifacts) {
                    val target = File(modelDir, artifact.fileName)
                    if (target.isFile) continue
                    installVerified(artifact, File(staging, artifact.fileName), target)
                }
            } finally {
                // A bad file must be fetched again, so drop the finished downloads either way.
                cancelDownload()
            }
        }
    }

    /** Removes queued or finished downloads together with their staged files. */
    fun cancelDownload() {
        val ids = downloadIds()
        if (ids.isNotEmpty()) downloadManager.remove(*ids)
        prefs.edit().remove(KEY_DOWNLOAD_IDS).apply()
        appContext.getExternalFilesDir(STAGING_DIR)?.listFiles()?.forEach { it.delete() }
    }

    private fun installVerified(artifact: ModelArtifact, source: File, target: File) {
        val tempFile = File(target.parentFile, "${target.name}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            source.inputStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                    }
                }
            }
            if (copied != artifact.sizeBytes) {
                throw CorruptModelFileException("Size mismatch for ${artifact.fileName}: expected ${artifact.sizeBytes}, got $copied")
            }
            val actualSha = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
            if (!actualSha.equals(artifact.sha256, ignoreCase = true)) {
                throw CorruptModelFileException("SHA-256 mismatch for ${artifact.fileName}: expected ${artifact.sha256}, got $actualSha")
            }
            if (!tempFile.renameTo(target)) {
                throw IOException("Failed to move ${artifact.fileName} into the model directory")
            }
        } finally {
            tempFile.delete()
        }
    }

    /** Counts cache the system can clear, unlike File.usableSpace. */
    private fun allocatableBytes(dir: File): Long =
        storageManager.getAllocatableBytes(storageManager.getUuidForPath(dir))

    private fun stagingDir(): File =
        appContext.getExternalFilesDir(STAGING_DIR) ?: throw IOException("Shared storage is not available")

    private fun downloadIds(): LongArray =
        prefs.getString(KEY_DOWNLOAD_IDS, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?.toLongArray()
            ?: LongArray(0)

    companion object {
        /** The download was removed outside the app, for example from the system Downloads list. */
        const val REASON_REMOVED = -1

        private const val PREFS_NAME = "gigaam_model_download"
        private const val KEY_DOWNLOAD_IDS = "download_ids"
        private const val STAGING_DIR = "model-download"
        private const val SPACE_MARGIN_BYTES = 20L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val finalizeLock = Mutex()
    }
}
