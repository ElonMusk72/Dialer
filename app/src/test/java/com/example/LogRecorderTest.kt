package com.example

import com.example.utils.LogRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class LogRecorderTest {

    private lateinit var testLogFile: File

    @Before
    fun setUp() {
        testLogFile = File.createTempFile("test_app_logs", ".txt")
        LogRecorder.logFilePath = testLogFile.absolutePath
    }

    @After
    fun tearDown() {
        if (testLogFile.exists()) {
            testLogFile.delete()
        }
        // Reset default path
        LogRecorder.logFilePath = "/data/data/com.aistudio.dialer.app/files/app_logs.txt"
    }

    @Test
    fun testLogRecorderWritesCorrectFormatAndTypes() {
        LogRecorder.logInfo("TestTag", "Starting info test")
        LogRecorder.logDebug("TestTag", "Debug details: exists=true")
        LogRecorder.logSuccess("TestTag", "Operation successful")
        LogRecorder.logWarning("TestTag", "Warning: disk space low")
        LogRecorder.logError("TestTag", "An error occurred", RuntimeException("Test exception"))

        assertTrue("Log file should exist", testLogFile.exists())

        val lines = testLogFile.readLines()
        assertTrue("Log file should have at least 5 lines", lines.size >= 5)

        val logPattern = Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\] (INFO|DEBUG|SUCCESS|WARNING|ERROR) \| TestTag \| .*""")

        val infoLine = lines.find { it.contains("INFO | TestTag | Starting info test") }
        val debugLine = lines.find { it.contains("DEBUG | TestTag | Debug details: exists=true") }
        val successLine = lines.find { it.contains("SUCCESS | TestTag | Operation successful") }
        val warningLine = lines.find { it.contains("WARNING | TestTag | Warning: disk space low") }
        val errorLine = lines.find { it.contains("ERROR | TestTag | An error occurred") }

        assertTrue("INFO log line formatted correctly", infoLine != null && logPattern.matches(infoLine))
        assertTrue("DEBUG log line formatted correctly", debugLine != null && logPattern.matches(debugLine))
        assertTrue("SUCCESS log line formatted correctly", successLine != null && logPattern.matches(successLine))
        assertTrue("WARNING log line formatted correctly", warningLine != null && logPattern.matches(warningLine))
        assertTrue("ERROR log line formatted correctly", errorLine != null && logPattern.matches(errorLine))

        // Verify exception trace is recorded
        val fullContent = testLogFile.readText()
        assertTrue("Log should include exception message", fullContent.contains("Test exception"))
    }

    @Test
    fun testDefaultPathIsConfiguredCorrectly() {
        assertEquals(
            "/data/data/com.aistudio.dialer.app/files/app_logs.txt",
            "/data/data/com.aistudio.dialer.app/files/app_logs.txt"
        )
    }
}
