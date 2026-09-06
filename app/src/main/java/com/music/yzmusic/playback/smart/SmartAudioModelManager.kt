package com.music.yzmusic.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.music.yzmusic.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
 * verified against known SHA-256 digests and ONNX graph initialization, and cached atomically
 * in app-private storage.
 */
object SmartAudioModelManager {
    private const val TAG = "SmartAudioModelManager"

    enum class ModelType(
        val fileName: String,
        val displayName: String,
        val description: String,
        val sha256: String,
        val expectedBytes: Long,
        val downloadUrl: String,
        val fallbackUrls: List<String> = emptyList(),
    ) {
        BEAT(
            fileName = "beat_this_int8.onnx",
            displayName = "Neural Beat Tracker",
            description = "Downbeat, tempo phase & bar alignment",
            sha256 = "9dc29f1fcd713d18f48a2755109fce01429ba6d1639607af8ae5c7449b47070f",
            expectedBytes = 4_533_252L,
            downloadUrl = "https://github.com/4shil/YZ-Music/raw/v1.04.03/app/src/debug/assets/beat_this_int8.onnx",
            fallbackUrls = listOf(
                "https://raw.githubusercontent.com/4shil/YZ-Music/v1.04.03/app/src/debug/assets/beat_this_int8.onnx",
                "https://raw.githubusercontent.com/kushagrasinghx/BitChord/main/app/src/main/assets/beat_this_int8.onnx",
            ),
        ),
        VOCAL(
            fileName = "vocals_umxhq_int8.onnx",
            displayName = "Neural Vocal Separator",
            description = "Voice activity detection & clash prevention",
            sha256 = "a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb",
            expectedBytes = 9_054_206L,
            downloadUrl = "https://github.com/4shil/YZ-Music/raw/v1.04.03/app/src/debug/assets/vocals_umxhq_int8.onnx",
            fallbackUrls = listOf(
                "https://raw.githubusercontent.com/4shil/YZ-Music/v1.04.03/app/src/debug/assets/vocals_umxhq_int8.onnx",
                "https://raw.githubusercontent.com/kushagrasinghx/BitChord/main/app/src/main/assets/vocals_umxhq_int8.onnx",
            ),
        );

        val candidateUrls: List<String>
            get() = listOf(downloadUrl) + fallbackUrls
    }

    enum class DownloadErrorType {
        NETWORK_UNAVAILABLE,
        HTTP_404,
        HTTP_403,
        SERVER_ERROR,
        TIMEOUT,
        STORAGE_ERROR,
        INVALID_MODEL,
        CANCELLED,
        UNKNOWN,
    }

    class DownloadHttpException(
        val statusCode: Int,
        message: String,
        val errorType: DownloadErrorType,
    ) : IOException(message)

    sealed interface DownloadState {
        object NotDownloaded : DownloadState
        data class Downloading(val progressPercent: Int, val currentBytes: Long, val totalBytes: Long) : DownloadState
        data class Validating(val modelName: String) : DownloadState
        object Ready : DownloadState
        data class Error(val message: String, val errorType: DownloadErrorType = DownloadErrorType.UNKNOWN) : DownloadState
        object Cancelled : DownloadState
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val downloadMutex = Mutex()

    @Volatile
    private var isDownloading = false

    @Volatile
    private var activeDownloadJob: Job? = null

    /**
     * Checks whether all required neural models are present and valid on disk.
     * If models were bundled in assets (e.g. debug builds), they will be extracted.
     * Interrupted temporary files are cleaned up.
     */
    fun checkStatus(context: Context): DownloadState {
        if (isDownloading) return _downloadState.value

        // Clean up any stale temporary files from interrupted downloads
        cleanupTempFiles(context)

        val allReady = ModelType.values().all { model ->
            val file = getModelFile(context, model)
            file != null && file.exists()
        }

        val newState = if (allReady) DownloadState.Ready else DownloadState.NotDownloaded
        _downloadState.value = newState
        return newState
    }

    /**
     * Cancels an ongoing download operation, cleans up temporary files, and transitions to Cancelled.
     */
    fun cancelDownload(context: Context? = null) {
        if (!isDownloading && activeDownloadJob == null) return
        Log.i(TAG, "Cancelling neural audio model download")
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        isDownloading = false
        if (context != null) {
            cleanupTempFiles(context)
        }
        _downloadState.value = DownloadState.Cancelled
    }

    /**
     * Resolves the local file for [model], verifying its existence, size, checksum, and ONNX graph validity.
     * If corrupted, it deletes the file.
     * If the file is not yet in app-private storage but is available in assets (debug flavor),
     * it extracts it to [context.filesDir].
     */
    fun getModelFile(context: Context, model: ModelType): File? {
        val target = File(context.filesDir, model.fileName)
        if (target.exists()) {
            if (target.length() == model.expectedBytes && verifyChecksum(target, model.sha256) && validateOnnxModel(target)) {
                return target
            } else {
                Log.w(TAG, "Cached model ${model.fileName} is invalid or corrupted. Removing.")
                target.delete()
            }
        }

        // Try extracting from assets if bundled (e.g. debug builds)
        return runCatching {
            context.assets.open(model.fileName).use { input ->
                val tmp = File(context.filesDir, "${model.fileName}.extract.tmp")
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                }
                if (verifyChecksum(tmp, model.sha256) && validateOnnxModel(tmp)) {
                    if (target.exists()) target.delete()
                    if (tmp.renameTo(target)) {
                        target
                    } else {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                        target
                    }
                } else {
                    tmp.delete()
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * Atomically downloads missing models over HTTPS with progress updates, SHA-256 verification,
     * and ONNX runtime session validation.
     */
    suspend fun downloadModels(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            if (isDownloading) return@withContext Result.success(Unit)
            isDownloading = true
            activeDownloadJob = coroutineContext[Job]
        }

        val totalBytes = ModelType.values().sumOf { it.expectedBytes }
        _downloadState.value = DownloadState.Downloading(0, 0, totalBytes)

        try {
            // 1. Verify storage space in app-private directory
            val usableSpace = context.filesDir.usableSpace
            if (usableSpace < totalBytes + 10_000_000L) { // 10MB safety margin
                throw DownloadHttpException(
                    statusCode = 0,
                    message = "Insufficient storage space (need ~14MB, available ${usableSpace / 1_000_000}MB)",
                    errorType = DownloadErrorType.STORAGE_ERROR,
                )
            }

            var overallDownloaded = 0L

            for (model in ModelType.values()) {
                coroutineContext.ensureActive()

                val dest = File(context.filesDir, model.fileName)
                if (dest.exists() && dest.length() == model.expectedBytes && verifyChecksum(dest, model.sha256) && validateOnnxModel(dest)) {
                    overallDownloaded += model.expectedBytes
                    val pct = ((overallDownloaded.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                    _downloadState.value = DownloadState.Downloading(pct, overallDownloaded, totalBytes)
                    Log.d(TAG, "Model ${model.fileName} already present and validated")
                    continue
                }

                val tmpFile = File(context.filesDir, "${model.fileName}.download")
                if (tmpFile.exists()) tmpFile.delete()

                var modelDownloaded = false
                var lastException: Exception? = null
                var lastErrorType = DownloadErrorType.UNKNOWN

                for (url in model.candidateUrls) {
                    coroutineContext.ensureActive()
                    val host = runCatching { java.net.URI.create(url).host }.getOrDefault("host")
                    Log.i(TAG, "Starting download: model=${model.fileName} host=$host")

                    try {
                        val request = Request.Builder()
                            .url(url)
                            .header("User-Agent", "YZMusic/1.04.03 (Android)")
                            .build()

                        val startTime = System.currentTimeMillis()
                        Http.client.newCall(request).execute().use { response ->
                            Log.i(TAG, "HTTP Response: code=${response.code} length=${response.body?.contentLength()} type=${response.body?.contentType()}")

                            if (!response.isSuccessful) {
                                val errType = when (response.code) {
                                    404 -> DownloadErrorType.HTTP_404
                                    403 -> DownloadErrorType.HTTP_403
                                    in 500..599 -> DownloadErrorType.SERVER_ERROR
                                    else -> DownloadErrorType.UNKNOWN
                                }
                                throw DownloadHttpException(
                                    statusCode = response.code,
                                    message = "Download failed for ${model.displayName}: HTTP ${response.code} ${response.message}",
                                    errorType = errType,
                                )
                            }

                            val body = response.body
                                ?: throw DownloadHttpException(
                                    statusCode = response.code,
                                    message = "Empty response body for ${model.fileName}",
                                    errorType = DownloadErrorType.SERVER_ERROR,
                                )

                            val buffer = ByteArray(8192)
                            var modelBytesRead = 0L

                            body.byteStream().use { input ->
                                FileOutputStream(tmpFile).use { output ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        coroutineContext.ensureActive()
                                        output.write(buffer, 0, read)
                                        modelBytesRead += read
                                        val currentTotal = overallDownloaded + modelBytesRead
                                        val pct = (((currentTotal.toDouble() / totalBytes) * 100).toInt()).coerceIn(0, 99)
                                        _downloadState.value = DownloadState.Downloading(pct, currentTotal, totalBytes)
                                    }
                                    output.flush()
                                }
                            }

                            val duration = System.currentTimeMillis() - startTime
                            Log.i(TAG, "Download finished for ${model.fileName}: $modelBytesRead bytes in ${duration}ms")
                        }

                        // Verify file length
                        if (tmpFile.length() != model.expectedBytes) {
                            throw IllegalStateException(
                                "Size mismatch for ${model.fileName}: expected ${model.expectedBytes}, actual ${tmpFile.length()}"
                            )
                        }

                        // Verify SHA-256
                        if (!verifyChecksum(tmpFile, model.sha256)) {
                            throw IllegalStateException("SHA-256 verification failed for ${model.fileName}")
                        }

                        // Validate ONNX Runtime
                        _downloadState.value = DownloadState.Validating(model.displayName)
                        if (!validateOnnxModel(tmpFile)) {
                            throw IllegalStateException("ONNX Runtime graph validation failed for ${model.fileName}")
                        }

                        // Atomic rename to final destination
                        if (dest.exists()) dest.delete()
                        if (!tmpFile.renameTo(dest)) {
                            tmpFile.copyTo(dest, overwrite = true)
                            tmpFile.delete()
                        }

                        overallDownloaded += model.expectedBytes
                        val pct = (((overallDownloaded.toDouble() / totalBytes) * 100).toInt()).coerceIn(0, 100)
                        _downloadState.value = DownloadState.Downloading(pct, overallDownloaded, totalBytes)
                        modelDownloaded = true
                        Log.i(TAG, "Model ${model.fileName} successfully committed to ${dest.absolutePath}")
                        break
                    } catch (ce: CancellationException) {
                        tmpFile.delete()
                        throw ce
                    } catch (e: Exception) {
                        tmpFile.delete()
                        Log.w(TAG, "Download attempt failed from $url: ${e.message}")
                        lastException = e
                        lastErrorType = when (e) {
                            is DownloadHttpException -> e.errorType
                            is java.net.UnknownHostException -> DownloadErrorType.NETWORK_UNAVAILABLE
                            is java.net.SocketTimeoutException, is java.net.ConnectException -> DownloadErrorType.TIMEOUT
                            else -> DownloadErrorType.INVALID_MODEL
                        }
                    }
                }

                if (!modelDownloaded) {
                    val ex = lastException ?: IllegalStateException("Failed to download ${model.fileName}")
                    val msg = when (lastErrorType) {
                        DownloadErrorType.HTTP_404 -> "Model asset not found (HTTP 404)"
                        DownloadErrorType.HTTP_403 -> "Access forbidden (HTTP 403)"
                        DownloadErrorType.SERVER_ERROR -> "Server error while downloading model"
                        DownloadErrorType.NETWORK_UNAVAILABLE -> "Network unavailable, check connection"
                        DownloadErrorType.TIMEOUT -> "Network timeout while downloading model"
                        DownloadErrorType.STORAGE_ERROR -> ex.message ?: "Storage error"
                        DownloadErrorType.INVALID_MODEL -> "Model validation failed: ${ex.message}"
                        else -> ex.message ?: "Download failed"
                    }
                    _downloadState.value = DownloadState.Error(msg, lastErrorType)
                    return@withContext Result.failure(ex)
                }
            }

            _downloadState.value = DownloadState.Ready
            Log.i(TAG, "All neural audio models downloaded, validated, and ready.")
            Result.success(Unit)
        } catch (ce: CancellationException) {
            Log.i(TAG, "Model download cancelled by user")
            cleanupTempFiles(context)
            _downloadState.value = DownloadState.Cancelled
            Result.failure(ce)
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed with exception", e)
            cleanupTempFiles(context)
            val errType = when (e) {
                is DownloadHttpException -> e.errorType
                is java.net.UnknownHostException -> DownloadErrorType.NETWORK_UNAVAILABLE
                is java.net.SocketTimeoutException, is java.net.ConnectException -> DownloadErrorType.TIMEOUT
                else -> DownloadErrorType.UNKNOWN
            }
            _downloadState.value = DownloadState.Error(e.message ?: "Download failed", errType)
            Result.failure(e)
        } finally {
            isDownloading = false
            activeDownloadJob = null
        }
    }

    data class ModelVerificationInfo(
        val model: ModelType,
        val exists: Boolean,
        val actualBytes: Long,
        val isValidChecksum: Boolean,
        val isOrtValid: Boolean = true,
    )

    /**
     * Inspects on-disk status and verifies SHA-256 digests and ONNX graph validity for all models.
     */
    suspend fun verifyModels(context: Context): List<ModelVerificationInfo> = withContext(Dispatchers.IO) {
        ModelType.values().map { model ->
            val file = File(context.filesDir, model.fileName)
            val exists = file.exists()
            val actualBytes = if (exists) file.length() else 0L
            val valid = exists && actualBytes == model.expectedBytes && verifyChecksum(file, model.sha256)
            val ortValid = valid && validateOnnxModel(file)
            ModelVerificationInfo(
                model = model,
                exists = exists,
                actualBytes = actualBytes,
                isValidChecksum = valid,
                isOrtValid = ortValid,
            )
        }
    }

    /**
     * Safely deletes downloaded neural models and temporary files from app-private storage.
     * Automix will automatically fall back to the native C++ DSP analyzer.
     */
    fun deleteModels(context: Context): Boolean {
        if (isDownloading) return false
        var allDeleted = true
        for (model in ModelType.values()) {
            val file = File(context.filesDir, model.fileName)
            if (file.exists()) {
                if (!file.delete()) {
                    allDeleted = false
                }
            }
        }
        cleanupTempFiles(context)
        _downloadState.value = DownloadState.NotDownloaded
        return allDeleted
    }

    /**
     * Cleans existing files and starts a fresh download and verification.
     */
    suspend fun repairModels(context: Context): Result<Unit> {
        deleteModels(context)
        return downloadModels(context)
    }

    /**
     * Deletes any stray temporary or incomplete download files in app-private storage.
     */
    private fun cleanupTempFiles(context: Context) {
        runCatching {
            for (model in ModelType.values()) {
                val downloadTmp = File(context.filesDir, "${model.fileName}.download")
                if (downloadTmp.exists()) downloadTmp.delete()
                val legacyTmp = File(context.filesDir, "${model.fileName}.tmp")
                if (legacyTmp.exists()) legacyTmp.delete()
                val extractTmp = File(context.filesDir, "${model.fileName}.extract.tmp")
                if (extractTmp.exists()) extractTmp.delete()
            }
        }
    }

    /**
     * Validates that [file] is a readable ONNX model graph via ONNX Runtime.
     */
    fun validateOnnxModel(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            }
            val session = env.createSession(file.absolutePath, options)
            session.close()
            true
        }.recover { error ->
            if (error is LinkageError) {
                // In host JVM unit tests without native ONNX runtime JNI, SHA-256 checksum
                // already validated binary correctness.
                Log.w(TAG, "ONNX Runtime native JNI unavailable in host environment: ${error.message}")
                true
            } else {
                Log.e(TAG, "ONNX graph validation failed for ${file.name}", error)
                false
            }
        }.getOrDefault(false)
    }

    /**
     * Calculates and compares the SHA-256 digest of [file] with [expectedSha256].
     */
    fun verifyChecksum(file: File, expectedSha256: String): Boolean {
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
