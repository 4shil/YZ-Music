package com.music.yzmusic.playback.smart

import android.content.Context
import android.util.Log
import com.music.yzmusic.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * On-demand manager for Automix / Smart Audio neural models.
 *
 * The baseline APK does not bundle these models to keep download size minimal (~40 MB).
 * Normal playback and whole-track DSP analysis (tempo, key, energy curve, mix-out points,
 * and downbeat estimation) run entirely on the native DSP analyzer (`libyzmusic_analysis.so`).
 *
 * When the user enables Automix neural refinement, the models are downloaded on-demand over HTTPS,
 * verified against known SHA-256 digests, and cached atomically in app-private storage.
 */
object SmartAudioModelManager {
    private const val TAG = "SmartAudioModelManager"

    enum class ModelType(
        val fileName: String,
        val sha256: String,
        val expectedBytes: Long,
        val downloadUrl: String,
    ) {
        BEAT(
            fileName = "beat_this_int8.onnx",
            sha256 = "9dc29f1fcd713d18f48a2755109fce01429ba6d1639607af8ae5c7449b47070f",
            expectedBytes = 4_533_252L,
            downloadUrl = "https://github.com/4shil/YZ-Music/releases/download/v1.04.03/beat_this_int8.onnx",
        ),
        VOCAL(
            fileName = "vocals_umxhq_int8.onnx",
            sha256 = "a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb",
            expectedBytes = 9_054_206L,
            downloadUrl = "https://github.com/4shil/YZ-Music/releases/download/v1.04.03/vocals_umxhq_int8.onnx",
        ),
    }

    sealed interface DownloadState {
        object NotDownloaded : DownloadState
        data class Downloading(val progressPercent: Int, val currentBytes: Long, val totalBytes: Long) : DownloadState
        object Ready : DownloadState
        data class Error(val message: String) : DownloadState
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var isDownloading = false

    /**
     * Checks whether all required neural models are present and valid on disk.
     * If models were bundled in assets (e.g. debug builds), they will be extracted.
     */
    fun checkStatus(context: Context): DownloadState {
        if (isDownloading) return _downloadState.value

        val allReady = ModelType.values().all { model ->
            val file = getModelFile(context, model)
            file != null && file.exists()
        }

        val newState = if (allReady) DownloadState.Ready else DownloadState.NotDownloaded
        _downloadState.value = newState
        return newState
    }

    /**
     * Resolves the local file for [model], verifying its existence and checksum.
     * If the file is not yet in app-private storage but is available in assets (debug flavor),
     * it extracts it to [context.filesDir].
     */
    fun getModelFile(context: Context, model: ModelType): File? {
        val target = File(context.filesDir, model.fileName)
        if (target.exists() && target.length() == model.expectedBytes) {
            return target
        }

        // Try extracting from assets if bundled (e.g. debug builds)
        return runCatching {
            context.assets.open(model.fileName).use { input ->
                val tmp = File(context.filesDir, "${model.fileName}.extract.tmp")
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                }
                if (verifyChecksum(tmp, model.sha256)) {
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                    target
                } else {
                    tmp.delete()
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * Atomically downloads missing models over HTTPS with progress updates and SHA-256 verification.
     */
    suspend fun downloadModels(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        if (isDownloading) return@withContext Result.success(Unit)
        isDownloading = true
        _downloadState.value = DownloadState.Downloading(0, 0, ModelType.values().sumOf { it.expectedBytes })

        try {
            val totalBytes = ModelType.values().sumOf { it.expectedBytes }
            var overallDownloaded = 0L

            for (model in ModelType.values()) {
                val dest = File(context.filesDir, model.fileName)
                if (dest.exists() && dest.length() == model.expectedBytes && verifyChecksum(dest, model.sha256)) {
                    overallDownloaded += model.expectedBytes
                    val pct = ((overallDownloaded.toDouble() / totalBytes) * 100).toInt()
                    _downloadState.value = DownloadState.Downloading(pct, overallDownloaded, totalBytes)
                    continue
                }

                val tmpFile = File(context.filesDir, "${model.fileName}.tmp")
                if (tmpFile.exists()) tmpFile.delete()

                val request = Request.Builder()
                    .url(model.downloadUrl)
                    .build()

                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Failed to download ${model.fileName}: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response body for ${model.fileName}")
                    val buffer = ByteArray(8192)

                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                overallDownloaded += read
                                val pct = (((overallDownloaded.toDouble() / totalBytes) * 100).toInt()).coerceIn(0, 99)
                                _downloadState.value = DownloadState.Downloading(pct, overallDownloaded, totalBytes)
                            }
                        }
                    }
                }

                if (!verifyChecksum(tmpFile, model.sha256)) {
                    tmpFile.delete()
                    throw IllegalStateException("SHA-256 verification failed for ${model.fileName}")
                }

                if (dest.exists()) dest.delete()
                if (!tmpFile.renameTo(dest)) {
                    throw IllegalStateException("Failed to commit model file to ${dest.absolutePath}")
                }
            }

            _downloadState.value = DownloadState.Ready
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            Result.failure(e)
        } finally {
            isDownloading = false
        }
    }

    private fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val hashHex = digest.digest().joinToString("") { "%02x".format(it) }
            hashHex.equals(expectedSha256, ignoreCase = true)
        }.getOrDefault(false)
    }
}
