// SPDX-License-Identifier: GPL-3.0-or-later
package com.voiceskip.domain.usecase

import android.content.Context
import android.net.Uri
import com.voiceskip.data.source.WhisperDataSource
import com.voiceskip.data.source.TranscriptionThreadCounts
import com.voiceskip.media.UrlMediaDownloader
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UrlTranscriptionCleanupTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `failed downloads clean partial media and never start whisper`() = runTest {
        val context = mockk<Context> { every { cacheDir } returns folder.root }
        val uri = mockk<Uri> {
            every { scheme } returns "https"
            every { toString() } returns "https://example.com/audio"
        }
        val whisper = mockk<WhisperDataSource>(relaxed = true)
        val downloader = mockk<UrlMediaDownloader>()
        coEvery { downloader.download(any(), any(), any()) } coAnswers {
            val dir = secondArg<File>()
            dir.mkdirs()
            File(dir, "audio.part").writeText("partial")
            throw IOException("offline")
        }
        val useCase = FileTranscriptionUseCase(context, whisper, downloader)
        val error = runCatching {
            useCase.execute(FileTranscriptionUseCase.Source.FromUri(uri),
                TranscriptionThreadCounts(2, 2), null, false, false).collect()
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(File(folder.root, "url-media").listFiles().orEmpty().isEmpty())
        verify(exactly = 0) { whisper.startStream(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `cancellation removes partial download and propagates to downloader`() = runTest {
        val context = mockk<Context> { every { cacheDir } returns folder.root }
        val uri = mockk<Uri> {
            every { scheme } returns "https"
            every { toString() } returns "https://example.com/audio"
        }
        val downloader = mockk<UrlMediaDownloader>()
        var cancelled = false
        coEvery { downloader.download(any(), any(), any()) } coAnswers {
            val dir = secondArg<File>()
            dir.mkdirs()
            File(dir, "audio.part").writeText("partial")
            try { awaitCancellation() } finally { cancelled = true }
        }
        val useCase = FileTranscriptionUseCase(context, mockk(relaxed = true), downloader)
        val job = launch {
            useCase.execute(FileTranscriptionUseCase.Source.FromUri(uri),
                TranscriptionThreadCounts(2, 2), null, false, false).collect()
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(File(folder.root, "url-media").listFiles().orEmpty().isEmpty())
    }
}
