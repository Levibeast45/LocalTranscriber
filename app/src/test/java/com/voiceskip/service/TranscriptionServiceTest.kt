// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.service

import com.google.common.truth.Truth.assertThat
import com.voiceskip.data.repository.TranscriptionState
import com.voiceskip.fake.FakeTranscriptionRepository
import org.junit.Test

class TranscriptionServiceTest {

    @Test
    fun `active work cancellation routes live recording to recording cancellation`() {
        val repository = FakeTranscriptionRepository().apply {
            setState(
                TranscriptionState.LiveRecording(
                    durationMs = 0,
                    amplitude = 0f,
                    progress = 0,
                    currentSegment = null,
                    segments = emptyList()
                )
            )
        }

        cancelActiveWork(repository)

        assertThat(repository.cancelRecordingCalled).isTrue()
        assertThat(repository.cancelTranscriptionCalled).isFalse()
        assertThat(repository.state.value).isEqualTo(TranscriptionState.Idle)
    }

    @Test
    fun `active work cancellation routes file states to transcription cancellation`() {
        val activeStates = listOf(
            TranscriptionState.Transcribing(
                progress = 10,
                currentSegment = null,
                segments = emptyList(),
                downloading = true
            ),
            TranscriptionState.Transcribing(
                progress = 25,
                currentSegment = null,
                segments = emptyList()
            ),
            TranscriptionState.FinishingTranscription(
                progress = 75,
                currentSegment = null,
                segments = emptyList()
            )
        )

        activeStates.forEach { state ->
            val repository = FakeTranscriptionRepository().apply { setState(state) }

            cancelActiveWork(repository)

            assertThat(repository.cancelTranscriptionCalled).isTrue()
            assertThat(repository.cancelRecordingCalled).isFalse()
            assertThat(repository.state.value).isEqualTo(TranscriptionState.Idle)
        }
    }

    @Test
    fun `active work cancellation leaves inactive states unchanged`() {
        val inactiveStates = listOf(
            TranscriptionState.Idle,
            TranscriptionState.Complete(
                text = "complete",
                segments = emptyList(),
                detectedLanguage = null,
                audioLengthMs = 1,
                processingTimeMs = 1
            ),
            TranscriptionState.Error(
                message = "failed",
                recoverable = true
            )
        )

        inactiveStates.forEach { state ->
            val repository = FakeTranscriptionRepository().apply { setState(state) }

            cancelActiveWork(repository)

            assertThat(repository.cancelRecordingCalled).isFalse()
            assertThat(repository.cancelTranscriptionCalled).isFalse()
            assertThat(repository.state.value).isEqualTo(state)
        }
    }
}
