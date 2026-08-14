package com.example

import com.example.service.Mp4VideoGenerator
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testMp4VideoGeneratorFallback() {
        val tempFile = File.createTempFile("test_rec_", ".mp4")
        tempFile.deleteOnExit()

        val size = Mp4VideoGenerator.generateChunkVideo(
            outputFile = tempFile,
            durationSeconds = 60L,
            chunkIndex = 1,
            timeRangeTag = "11:00 AM - 12:00 PM",
            resolution = "720p"
        )

        assertTrue("Generated MP4 must be greater than 0 bytes", size > 1024)
        assertTrue("Generated file must exist", tempFile.exists())
        assertEquals(tempFile.length(), size)
    }
}
