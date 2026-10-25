package com.faka.invisible

import com.faka.invisible.voicekit.*

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadTest {
    @Test fun progressTextShowsPercentAndGigabytes() {
        assertEquals("41%  (1.1 of 2.6 GB)", ModelDownload.progressText(1_070_000_000L, 2_588_000_000L))
        assertEquals("Starting…", ModelDownload.progressText(0, 0))
    }
}
