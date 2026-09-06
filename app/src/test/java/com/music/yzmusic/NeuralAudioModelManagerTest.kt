package com.music.yzmusic

import com.music.yzmusic.data.Http
import com.music.yzmusic.playback.smart.SmartAudioModelManager
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class NeuralAudioModelManagerTest {

    @Test
    fun `test ModelType metadata is valid and accurate`() {
        val models = SmartAudioModelManager.ModelType.values()
        assertEquals(2, models.size)

        val beat = SmartAudioModelManager.ModelType.BEAT
        assertEquals("beat_this_int8.onnx", beat.fileName)
        assertEquals("Neural Beat Tracker", beat.displayName)
        assertEquals(4_533_252L, beat.expectedBytes)
        assertEquals("9dc29f1fcd713d18f48a2755109fce01429ba6d1639607af8ae5c7449b47070f", beat.sha256)
        assertTrue(beat.downloadUrl.startsWith("https://github.com/"))
        assertTrue(beat.candidateUrls.size >= 2)
        assertTrue(beat.candidateUrls.all { it.endsWith("beat_this_int8.onnx") })

        val vocal = SmartAudioModelManager.ModelType.VOCAL
        assertEquals("vocals_umxhq_int8.onnx", vocal.fileName)
        assertEquals("Neural Vocal Separator", vocal.displayName)
        assertEquals(9_054_206L, vocal.expectedBytes)
        assertEquals("a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb", vocal.sha256)
        assertTrue(vocal.downloadUrl.startsWith("https://github.com/"))
        assertTrue(vocal.candidateUrls.size >= 2)
        assertTrue(vocal.candidateUrls.all { it.endsWith("vocals_umxhq_int8.onnx") })

        val totalBytes = models.sumOf { it.expectedBytes }
        assertEquals(13_587_458L, totalBytes)
        val mb = totalBytes / 1_000_000.0
        assertTrue("Total size should be ~13.5 - 13.6 MB", mb in 13.5..13.7)
    }

    @Test
    fun `test DownloadState sealed interface hierarchy and new states`() {
        val notDownloaded: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.NotDownloaded
        val ready: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.Ready
        val downloading: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.Downloading(
            progressPercent = 50,
            currentBytes = 6_793_729L,
            totalBytes = 13_587_458L,
        )
        val validating: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.Validating("Neural Beat Tracker")
        val cancelled: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.Cancelled
        val error: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.Error(
            message = "HTTP 404 Not Found",
            errorType = SmartAudioModelManager.DownloadErrorType.HTTP_404,
        )

        assertTrue(notDownloaded is SmartAudioModelManager.DownloadState.NotDownloaded)
        assertTrue(ready is SmartAudioModelManager.DownloadState.Ready)
        assertTrue(downloading is SmartAudioModelManager.DownloadState.Downloading)
        assertTrue(validating is SmartAudioModelManager.DownloadState.Validating)
        assertTrue(cancelled is SmartAudioModelManager.DownloadState.Cancelled)
        assertTrue(error is SmartAudioModelManager.DownloadState.Error)

        assertEquals(50, (downloading as SmartAudioModelManager.DownloadState.Downloading).progressPercent)
        assertEquals("Neural Beat Tracker", (validating as SmartAudioModelManager.DownloadState.Validating).modelName)
        assertEquals("HTTP 404 Not Found", (error as SmartAudioModelManager.DownloadState.Error).message)
        assertEquals(SmartAudioModelManager.DownloadErrorType.HTTP_404, error.errorType)
    }

    @Test
    fun `test DownloadErrorType comprehensive classification`() {
        val types = SmartAudioModelManager.DownloadErrorType.values()
        assertTrue(types.contains(SmartAudioModelManager.DownloadErrorType.NETWORK_UNAVAILABLE))
        assertTrue(types.contains(SmartAudioModelManager.DownloadErrorType.HTTP_404))
        assertTrue(types.contains(SmartAudioModelManager.DownloadErrorType.HTTP_403))
        assertTrue(types.contains(SmartAudioModelManager.DownloadErrorType.SERVER_ERROR))
        assertTrue(types.contains(SmartAudioModelManager.DownloadErrorType.TIMEOUT))
        assertTrue(types.contains(SmartAudioModelManager.DownloadErrorType.STORAGE_ERROR))
        assertTrue(types.contains(SmartAudioModelManager.DownloadErrorType.INVALID_MODEL))
        assertTrue(types.contains(SmartAudioModelManager.DownloadErrorType.CANCELLED))
        assertTrue(types.contains(SmartAudioModelManager.DownloadErrorType.UNKNOWN))
    }

    @Test
    fun `test verifyChecksum with valid and corrupted files`() {
        val tempFile = File.createTempFile("model_test", ".tmp")
        try {
            val content = "test neural model payload for verification"
            tempFile.writeText(content)

            val digest = MessageDigest.getInstance("SHA-256")
            val expectedHash = digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

            assertTrue(SmartAudioModelManager.verifyChecksum(tempFile, expectedHash))
            assertFalse(SmartAudioModelManager.verifyChecksum(tempFile, "0000000000000000000000000000000000000000000000000000000000000000"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `test validateOnnxModel rejects non-existent and empty files`() {
        val nonExistent = File("non_existent_model.onnx")
        assertFalse(SmartAudioModelManager.validateOnnxModel(nonExistent))

        val emptyFile = File.createTempFile("empty_model", ".onnx")
        try {
            assertFalse(SmartAudioModelManager.validateOnnxModel(emptyFile))
        } finally {
            emptyFile.delete()
        }
    }

    @Test
    fun `test ModelVerificationInfo data class with isOrtValid`() {
        val info = SmartAudioModelManager.ModelVerificationInfo(
            model = SmartAudioModelManager.ModelType.BEAT,
            exists = true,
            actualBytes = 4_533_252L,
            isValidChecksum = true,
            isOrtValid = true,
        )
        assertEquals(SmartAudioModelManager.ModelType.BEAT, info.model)
        assertTrue(info.exists)
        assertEquals(4_533_252L, info.actualBytes)
        assertTrue(info.isValidChecksum)
        assertTrue(info.isOrtValid)
    }

    @Test
    fun `test real model asset URLs are accessible and return valid binary data`() {
        for (model in SmartAudioModelManager.ModelType.values()) {
            val url = model.downloadUrl
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "YZMusic/1.04.03 (Android)")
                .build()

            Http.client.newCall(request).execute().use { response ->
                assertTrue("URL $url should succeed, got ${response.code}", response.isSuccessful)
                val body = response.body
                assertNotNull("Response body should not be null for $url", body)
                val contentLength = body!!.contentLength()
                assertEquals("Content-Length mismatch for ${model.fileName}", model.expectedBytes, contentLength)
            }
        }
    }

    @Test
    fun `test real download of beat model over HTTPS matches expected SHA-256`() {
        val beat = SmartAudioModelManager.ModelType.BEAT
        val tempFile = File.createTempFile("live_beat_test", ".download")
        try {
            val request = Request.Builder()
                .url(beat.downloadUrl)
                .header("User-Agent", "YZMusic/1.04.03 (Android)")
                .build()

            Http.client.newCall(request).execute().use { response ->
                assertTrue("Download should succeed", response.isSuccessful)
                val body = response.body
                assertNotNull("Response body should not be null", body)
                body!!.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            assertEquals(beat.expectedBytes, tempFile.length())
            assertTrue("Downloaded model SHA-256 must match expected hash", SmartAudioModelManager.verifyChecksum(tempFile, beat.sha256))
        } finally {
            tempFile.delete()
        }
    }
}
