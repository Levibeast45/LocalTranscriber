// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.voiceskip.TestDispatcherRule
import com.voiceskip.data.source.AudioDocumentDataSource
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlaybackRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var repository: AudioPlaybackRepositoryImpl
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var mediaPlayerFactory: MediaPlayerFactory
    private lateinit var documentDataSource: FakeAudioDocumentDataSource
    private lateinit var descriptor: AssetFileDescriptor
    private lateinit var uri: Uri
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var errorListener: MediaPlayer.OnErrorListener? = null

    @Before
    fun setup() {
        val preparedListenerSlot = slot<MediaPlayer.OnPreparedListener>()
        val errorListenerSlot = slot<MediaPlayer.OnErrorListener>()

        mediaPlayer = mockk(relaxed = true) {
            every { setDataSource(any<AssetFileDescriptor>()) } just Runs
            every { prepareAsync() } just Runs
            every { prepare() } just Runs
            every { start() } just Runs
            every { stop() } just Runs
            every { reset() } just Runs
            every { release() } just Runs
            every { isPlaying } returns true
            every { duration } returns 60000
            every { currentPosition } returns 0
            every { setOnPreparedListener(capture(preparedListenerSlot)) } answers {
                preparedListener = preparedListenerSlot.captured
            }
            every { setOnPreparedListener(null) } just Runs
            every { setOnCompletionListener(any()) } just Runs
            every { setOnErrorListener(capture(errorListenerSlot)) } answers {
                errorListener = errorListenerSlot.captured
            }
        }

        mediaPlayerFactory = mockk {
            every { create() } returns mediaPlayer
        }
        descriptor = mockk(relaxed = true)
        uri = mockk(relaxed = true)
        documentDataSource = FakeAudioDocumentDataSource(descriptor)

        repository = AudioPlaybackRepositoryImpl(
            audioDocumentDataSource = documentDataSource,
            mediaPlayerFactory = mediaPlayerFactory,
            mainDispatcher = dispatcherRule.testDispatcher
        )
    }

    @Test
    fun `preparePlayback waits for asynchronous preparation`() = runTest {
        val preparation = async { repository.preparePlayback(uri) }
        runCurrent()

        assertThat(preparation.isCompleted).isFalse()
        assertThat(repository.playbackState.value.isPrepared).isFalse()
        verify { mediaPlayer.prepareAsync() }
        verify(exactly = 0) { mediaPlayer.prepare() }

        preparedListener?.onPrepared(mediaPlayer)
        runCurrent()

        assertThat(preparation.isCompleted).isTrue()
        assertThat(repository.playbackState.value).isEqualTo(
            PlaybackState(isPrepared = true, durationMs = 60000)
        )
    }

    @Test
    fun `playback controls wait for asynchronous preparation`() = runTest {
        val preparation = async { repository.preparePlayback(uri) }
        runCurrent()

        repository.play()
        repository.pause()
        repository.togglePlayPause()
        repository.seekTo(70000)

        verify(exactly = 0) { mediaPlayer.isPlaying }
        verify(exactly = 0) { mediaPlayer.start() }
        verify(exactly = 0) { mediaPlayer.pause() }
        verify(exactly = 0) { mediaPlayer.duration }
        verify(exactly = 0) { mediaPlayer.seekTo(any<Int>()) }

        preparedListener?.onPrepared(mediaPlayer)
        runCurrent()
        preparation.await()

        repository.seekTo(70000)

        verify(exactly = 1) { mediaPlayer.seekTo(60000) }
        assertThat(repository.playbackState.value.currentPositionMs).isEqualTo(60000)
    }

    @Test
    fun `preparation error resets and releases the player`() = runTest {
        val preparation = async { runCatching { repository.preparePlayback(uri) } }
        runCurrent()

        errorListener?.onError(mediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 7)
        runCurrent()

        assertThat(preparation.await().isFailure).isTrue()
        assertThat(repository.playbackState.value).isEqualTo(PlaybackState())
        verify { mediaPlayer.release() }
    }

    @Test
    fun `stop cancels preparation and ignores its late callback`() = runTest {
        val preparation = async { repository.preparePlayback(uri) }
        runCurrent()

        repository.stopPlayback()
        runCurrent()
        preparedListener?.onPrepared(mediaPlayer)
        runCurrent()

        assertThat(preparation.isCancelled).isTrue()
        assertThat(repository.playbackState.value).isEqualTo(PlaybackState())
        verify { mediaPlayer.release() }
    }

    @Test
    fun `stop cancels a pending document open`() = runTest {
        val openStarted = CompletableDeferred<Unit>()
        documentDataSource.beforeOpen = {
            openStarted.complete(Unit)
            awaitCancellation()
        }
        val preparation = async { repository.preparePlayback(uri) }
        runCurrent()
        openStarted.await()

        repository.stopPlayback()
        runCurrent()

        assertThat(preparation.isCancelled).isTrue()
        verify(exactly = 0) { mediaPlayerFactory.create() }
    }

    @Test
    fun `cleanup does not stop non-playing player`() = runTest {
        every { mediaPlayer.isPlaying } returns false
        prepareSuccessfully()

        repository.cleanup()

        verify(exactly = 0) { mediaPlayer.stop() }
    }

    @Test
    fun `cleanup handles stop exception`() = runTest {
        every { mediaPlayer.stop() } throws IllegalStateException("Already stopped")
        prepareSuccessfully()

        repository.cleanup()

        verify { mediaPlayer.reset() }
        verify { mediaPlayer.release() }
    }

    @Test
    fun `cleanup handles reset exception`() = runTest {
        every { mediaPlayer.reset() } throws IllegalStateException("Cannot reset")
        prepareSuccessfully()

        repository.cleanup()

        verify { mediaPlayer.release() }
    }

    @Test
    fun `preparePlayback throws on setDataSource error`() = runTest {
        every { mediaPlayer.setDataSource(any<AssetFileDescriptor>()) } throws
            java.io.IOException("File not found")

        val result = runCatching { repository.preparePlayback(uri) }

        assertThat(result.isFailure).isTrue()
        verify { mediaPlayer.release() }
    }

    @Test
    fun `preparePlayback throws on prepareAsync error`() = runTest {
        every { mediaPlayer.prepareAsync() } throws java.io.IOException("Cannot prepare")

        val result = runCatching { repository.preparePlayback(uri) }

        assertThat(result.isFailure).isTrue()
        verify { mediaPlayer.release() }
    }

    private suspend fun TestScope.prepareSuccessfully() {
        val preparation = async { repository.preparePlayback(uri) }
        runCurrent()
        preparedListener?.onPrepared(mediaPlayer)
        runCurrent()
        preparation.await()
    }

    private class FakeAudioDocumentDataSource(
        private val descriptor: AssetFileDescriptor
    ) : AudioDocumentDataSource {
        var beforeOpen: suspend () -> Unit = {}

        override suspend fun <T> withOpenAudio(
            uri: Uri,
            block: suspend (AssetFileDescriptor) -> T
        ): T {
            beforeOpen()
            return block(descriptor)
        }

        override suspend fun getDisplayName(uri: Uri): String? = null

        override suspend fun takePersistableReadPermission(uri: Uri) = Unit
    }
}
