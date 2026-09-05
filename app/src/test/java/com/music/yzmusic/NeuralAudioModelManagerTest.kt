package com.music.yzmusic

import com.music.yzmusic.playback.smart.SmartAudioModelManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File

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

        val vocal = SmartAudioModelManager.ModelType.VOCAL
        assertEquals("vocals_umxhq_int8.onnx", vocal.fileName)
        assertEquals("Neural Vocal Separator", vocal.displayName)
        assertEquals(9_054_206L, vocal.expectedBytes)
        assertEquals("a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb", vocal.sha256)
        assertTrue(vocal.downloadUrl.startsWith("https://github.com/"))

        val totalBytes = models.sumOf { it.expectedBytes }
        assertEquals(13_587_458L, totalBytes)
        val mb = totalBytes / 1_000_000.0
        assertTrue("Total size should be ~13.5 - 13.6 MB", mb in 13.5..13.7)
    }

    @Test
    fun `test DownloadState sealed interface hierarchy`() {
        val notDownloaded: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.NotDownloaded
        val ready: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.Ready
        val downloading: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.Downloading(
            progressPercent = 50,
            currentBytes = 6_793_729L,
            totalBytes = 13_587_458L,
        )
        val error: SmartAudioModelManager.DownloadState = SmartAudioModelManager.DownloadState.Error("Network failure")

        assertTrue(notDownloaded is SmartAudioModelManager.DownloadState.NotDownloaded)
        assertTrue(ready is SmartAudioModelManager.DownloadState.Ready)
        assertTrue(downloading is SmartAudioModelManager.DownloadState.Downloading)
        assertTrue(error is SmartAudioModelManager.DownloadState.Error)

        assertEquals(50, (downloading as SmartAudioModelManager.DownloadState.Downloading).progressPercent)
        assertEquals("Network failure", (error as SmartAudioModelManager.DownloadState.Error).message)
    }

    @Test
    fun `test ModelVerificationInfo data class`() {
        val info = SmartAudioModelManager.ModelVerificationInfo(
            model = SmartAudioModelManager.ModelType.BEAT,
            exists = true,
            actualBytes = 4_533_252L,
            isValidChecksum = true,
        )
        assertEquals(SmartAudioModelManager.ModelType.BEAT, info.model)
        assertTrue(info.exists)
        assertEquals(4_533_252L, info.actualBytes)
        assertTrue(info.isValidChecksum)
    }
}
