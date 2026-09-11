// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.repository

import android.media.MediaPlayer
import android.net.Uri
import com.voiceskip.data.ErrorHandler
import com.voiceskip.data.source.AudioDocumentDataSource
import com.voiceskip.di.MainDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_TAG = "AudioPlaybackRepository"
private const val POSITION_UPDATE_INTERVAL_MS = 200L

@Singleton
class AudioPlaybackRepositoryImpl @Inject constructor(
    private val audioDocumentDataSource: AudioDocumentDataSource,
    private val mediaPlayerFactory: MediaPlayerFactory,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher
) : AudioPlaybackRepository {

    private var mediaPlayer: MediaPlayer? = null
    private var positionUpdateJob: Job? = null
    private var pendingPreparation: CompletableDeferred<Unit>? = null
    private var activePreparation: Deferred<Unit>? = null
    private var playbackGeneration = 0L
    private val coroutineScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    override suspend fun preparePlayback(uri: Uri) = coroutineScope {
        val preparation = async(
            context = mainDispatcher,
            start = CoroutineStart.LAZY
        ) {
            preparePlaybackOnMain(uri)
        }

        withContext(mainDispatcher) {
            activePreparation?.cancel()
            activePreparation = preparation
            preparation.start()
        }

        try {
            preparation.await()
        } finally {
            withContext(NonCancellable + mainDispatcher) {
                if (activePreparation === preparation) {
                    activePreparation = null
                }
            }
        }
    }

    private suspend fun preparePlaybackOnMain(uri: Uri) {
        val generation = ++playbackGeneration
        releaseMediaPlayer()
        stopPositionUpdates()
        _playbackState.value = PlaybackState()

        try {
            val player = audioDocumentDataSource.withOpenAudio(uri) { descriptor ->
                withContext(mainDispatcher) {
                    if (generation != playbackGeneration) {
                        null
                    } else {
                        mediaPlayerFactory.create().also { newPlayer ->
                            mediaPlayer = newPlayer
                            newPlayer.setOnCompletionListener { completedPlayer ->
                                if (completedPlayer === mediaPlayer) {
                                    _playbackState.value = _playbackState.value.copy(
                                        isPlaying = false,
                                        currentPositionMs = 0
                                    )
                                    stopPositionUpdates()
                                }
                            }
                            newPlayer.setDataSource(descriptor)
                        }
                    }
                }
            } ?: return

            awaitPrepared(player)

            if (generation != playbackGeneration || player !== mediaPlayer) {
                return
            }
            _playbackState.value = PlaybackState(
                isPrepared = true,
                durationMs = player.duration.toLong()
            )
        } catch (exception: CancellationException) {
            if (generation == playbackGeneration) {
                _playbackState.value = PlaybackState()
                releaseMediaPlayer()
            }
            throw exception
        } catch (exception: Exception) {
            val whisperError = ErrorHandler.handleError(exception)
            ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            if (generation == playbackGeneration) {
                _playbackState.value = PlaybackState()
                releaseMediaPlayer()
            }
            throw whisperError
        }
    }

    override fun play() {
        val player = preparedPlayer() ?: return
        if (!player.isPlaying) {
            player.start()
            _playbackState.value = _playbackState.value.copy(isPlaying = true)
            startPositionUpdates()
        }
    }

    override fun pause() {
        val player = preparedPlayer() ?: return
        if (player.isPlaying) {
            player.pause()
            _playbackState.value = _playbackState.value.copy(isPlaying = false)
            stopPositionUpdates()
        }
    }

    override fun togglePlayPause() {
        val player = preparedPlayer() ?: return
        if (player.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    override fun seekTo(positionMs: Long) {
        val player = preparedPlayer() ?: return
        val clampedPosition = positionMs.coerceIn(0, player.duration.toLong())
        player.seekTo(clampedPosition.toInt())
        _playbackState.value = _playbackState.value.copy(
            currentPositionMs = clampedPosition
        )
    }

    private fun preparedPlayer(): MediaPlayer? =
        mediaPlayer?.takeIf { _playbackState.value.isPrepared }

    override suspend fun stopPlayback() = withContext(mainDispatcher) {
        playbackGeneration++
        cancelActivePreparation()
        stopPositionUpdates()
        _playbackState.value = PlaybackState()
        releaseMediaPlayer()
    }

    override fun cleanup() {
        playbackGeneration++
        cancelActivePreparation()
        stopPositionUpdates()
        _playbackState.value = PlaybackState()
        releaseMediaPlayer()
    }

    override suspend fun getFileNameFromUri(uri: Uri): String? =
        audioDocumentDataSource.getDisplayName(uri)

    override suspend fun takePersistablePermission(uri: Uri) =
        audioDocumentDataSource.takePersistableReadPermission(uri)

    private suspend fun awaitPrepared(player: MediaPlayer) {
        val preparation = CompletableDeferred<Unit>()
        pendingPreparation = preparation

        player.setOnPreparedListener { preparedPlayer ->
            if (preparedPlayer === mediaPlayer) {
                preparation.complete(Unit)
            }
        }
        player.setOnErrorListener { failedPlayer, what, extra ->
            if (failedPlayer !== mediaPlayer) {
                true
            } else if (preparation.completeExceptionally(
                    IOException("MediaPlayer failed while preparing: what=$what extra=$extra")
                )
            ) {
                true
            } else {
                _playbackState.value = PlaybackState()
                stopPositionUpdates()
                playbackGeneration++
                releaseMediaPlayer()
                true
            }
        }

        try {
            player.prepareAsync()
            preparation.await()
        } finally {
            if (pendingPreparation === preparation) {
                pendingPreparation = null
            }
            if (player === mediaPlayer) {
                player.setOnPreparedListener(null)
            }
        }
    }

    private fun releaseMediaPlayer() {
        pendingPreparation?.cancel()
        pendingPreparation = null
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                val whisperError = ErrorHandler.handleError(e)
                ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            }

            try {
                player.reset()
            } catch (e: Exception) {
                val whisperError = ErrorHandler.handleError(e)
                ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            }

            try {
                player.release()
            } catch (e: Exception) {
                val whisperError = ErrorHandler.handleError(e)
                ErrorHandler.logError(LOG_TAG, whisperError, critical = false)
            }
        }
        mediaPlayer = null
    }

    private fun cancelActivePreparation() {
        activePreparation?.cancel()
        activePreparation = null
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateJob = coroutineScope.launch {
            while (isActive) {
                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        _playbackState.value = _playbackState.value.copy(
                            currentPositionMs = player.currentPosition.toLong()
                        )
                    }
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }
}

interface MediaPlayerFactory {
    fun create(): MediaPlayer
}

class DefaultMediaPlayerFactory @Inject constructor() : MediaPlayerFactory {
    override fun create(): MediaPlayer = MediaPlayer()
}
