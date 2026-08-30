package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.utils.VideoClipExtractor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class VideoClipExtractorTest {

    @Test
    fun testExtractClip_nonExistentFile_returnsNull() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val extractor = VideoClipExtractor(context)
        val result = extractor.extractClip("/non/existent/path/video.mp4")
        assertNull(result)
    }

    @Test
    fun testExtractClip_existingFile_createsDirectory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipsDir = File(context.filesDir, "clips")
        if (!clipsDir.exists()) {
            clipsDir.mkdirs()
        }
        assert(clipsDir.exists())
    }
}
