// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.ui.main

import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.voiceskip.StartupConfig
import com.voiceskip.TestDispatcherRule
import com.voiceskip.data.repository.PlaybackState
import com.voiceskip.data.repository.TranscriptionSource
import com.voiceskip.data.repository.TranscriptionState
import com.voiceskip.domain.usecase.AudioListenUseCase
import com.voiceskip.fake.FakeSavedTranscriptionRepository
import com.voiceskip.fake.FakeSettingsRepository
import com.voiceskip.fake.FakeTranscriptionRepository
import com.voiceskip.util.getParcelableExtraCompat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import com.voiceskip.data.UserPreferences
import com.voiceskip.domain.ModelManager
import com.voiceskip.domain.usecase.FormatSentencesUseCase
import com.voiceskip.service.ServiceLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private lateinit var viewModel: MainScreenViewModel
    private lateinit var fakeTranscriptionRepository: FakeTranscriptionRepository
    private lateinit var fakeSettingsRepository: FakeSettingsRepository
    private lateinit var fakeSavedTranscriptionRepository: FakeSavedTranscriptionRepository
    private lateinit var mockModelManager: ModelManager
    private lateinit var mockAudioListenUseCase: AudioListenUseCase
    private lateinit var mockServiceLauncher: ServiceLauncher
    private lateinit var mockAssetManager: AssetManager
    private lateinit var startupConfig: StartupConfig
    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var mockFormatSentencesUseCase: FormatSentencesUseCase

    private val modelStateFlow = MutableStateFlow<ModelManager.ModelState>(ModelManager.ModelState.NotLoaded)
    private val gpuFallbackReasonFlow = MutableStateFlow<ModelManager.GpuFallbackReason?>(null)
    private val turboFallbackReasonFlow = MutableStateFlow<ModelManager.TurboFallbackReason?>(null)
    private val modelFallbackReasonFlow = MutableStateFlow<ModelManager.ModelFallbackReason?>(null)
    private val playbackStateFlow = MutableStateFlow(PlaybackState())

    @Before
    fun setup() {
        mockkStatic("com.voiceskip.util.IntentExtensionsKt")
        fakeTranscriptionRepository = FakeTranscriptionRepository()
        fakeSettingsRepository = FakeSettingsRepository()
        fakeSavedTranscriptionRepository = FakeSavedTranscriptionRepository()

        mockModelManager = mockk(relaxed = true) {
            every { modelState } returns modelStateFlow
            every { gpuFallbackReason } returns gpuFallbackReasonFlow
            every { turboFallbackReason } returns turboFallbackReasonFlow
            every { modelFallbackReason } returns modelFallbackReasonFlow
            coEvery { loadModel(any()) } returns Result.success(Unit)
        }

        mockAudioListenUseCase = mockk(relaxed = true) {
            coEvery { stopPlayback() } just Runs
            coEvery { getFileNameFromUri(any()) } returns null
            coEvery { takePersistablePermission(any()) } just Runs
            every { playbackState } returns playbackStateFlow
        }

        mockServiceLauncher = mockk(relaxed = true) {
            coEvery { startRecording(any()) } just Runs
            coEvery { startFileTranscription(any(), any()) } just Runs
        }

        mockAssetManager = mockk(relaxed = true)

        startupConfig = StartupConfig().apply {
            skipModelLoad = true
        }

        savedStateHandle = SavedStateHandle()

        mockFormatSentencesUseCase = FormatSentencesUseCase()

        createViewModel()
    }

    private fun createViewModel() {
        viewModel = MainScreenViewModel(
            repository = fakeTranscriptionRepository,
            settingsRepository = fakeSettingsRepository,
            savedTranscriptionRepository = fakeSavedTranscriptionRepository,
            modelManager = mockModelManager,
            audioListenUseCase = mockAudioListenUseCase,
            serviceLauncher = mockServiceLauncher,
            assetManager = mockAssetManager,
            startupConfig = startupConfig,
            savedStateHandle = savedStateHandle,
            formatSentencesUseCase = mockFormatSentencesUseCase
        )
    }

    // =========================================================================
    // Model Loading States Tests
    // =========================================================================

    @Test
    fun `uiState transitions to LoadingModel when model is loading`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loading("models/test.bin", true)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.LoadingModel)
        }
    }

    @Test
    fun `uiState transitions to Ready when model loaded`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", "Test GPU")
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.Ready)
            assertThat(state.canTranscribe).isTrue()
        }
    }

    @Test
    fun `uiState transitions to Error on model load failure`() = runTest {
        val exception = RuntimeException("Model load failed")
        modelStateFlow.value = ModelManager.ModelState.Error(exception)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isInstanceOf(TranscriptionUiState.Error::class.java)
            assertThat((state.screenState as TranscriptionUiState.Error).message)
                .contains("Model load failed")
        }
    }

    // =========================================================================
    // Transcription Actions Tests
    // =========================================================================

    @Test
    fun `uiState transitions to LiveRecording during recording`() = runTest {
        fakeTranscriptionRepository.setState(
            TranscriptionState.LiveRecording(
                durationMs = 5000,
                amplitude = 0.7f,
                progress = 25,
                currentSegment = null,
                segments = emptyList()
            )
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.LiveRecording)
            assertThat(state.isRecording).isTrue()
            assertThat(state.recordingDurationMs).isEqualTo(5000)
        }
    }

    @Test
    fun `uiState transitions to Transcribing during file transcription`() = runTest {
        fakeTranscriptionRepository.setState(
            TranscriptionState.Transcribing(
                progress = 50,
                currentSegment = null,
                segments = emptyList()
            )
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.Transcribing)
        }
    }

    // =========================================================================
    // Progress & Results Tests
    // =========================================================================

    @Test
    fun `completion transitions uiState to Complete`() = runTest {
        fakeTranscriptionRepository.setState(
            TranscriptionState.Complete(
                text = "Complete transcription",
                processingTimeMs = 5000,
                audioLengthMs = 10000,
                segments = emptyList(),
                detectedLanguage = "en"
            )
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.Complete)
            assertThat(state.transcriptionResult).isNotNull()
            assertThat(state.transcriptionResult?.text).isEqualTo("Complete transcription")
        }
    }

    @Test
    fun `error transitions uiState to Error with message`() = runTest {
        fakeTranscriptionRepository.setState(
            TranscriptionState.Error("Test error message", recoverable = false)
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isInstanceOf(TranscriptionUiState.Error::class.java)
            assertThat((state.screenState as TranscriptionUiState.Error).message)
                .isEqualTo("Test error message")
            assertThat(state.errorMessage).isEqualTo("Test error message")
        }
    }

    @Test
    fun `GPU failure retries original file after CPU model reload`() = runTest {
        val uri = mockk<Uri> { every { scheme } returns "content" }
        val model = "ggml-small.bin"

        modelStateFlow.value = ModelManager.ModelState.Loaded(model, "Adreno 730")
        fakeTranscriptionRepository.setCurrentTranscriptionSource(
            TranscriptionSource.FileUri(uri)
        )
        fakeTranscriptionRepository.setSessionLanguage("fr")

        fakeTranscriptionRepository.setState(
            TranscriptionState.Error(
                message = "Vulkan failed",
                recoverable = true,
                gpuWasEnabled = true
            )
        )
        advanceUntilIdle()

        assertThat(fakeSettingsRepository.disableGpuAfterFailureCalled).isTrue()
        assertThat(fakeSettingsRepository.getCurrentSettings().gpuEnabled).isFalse()
        assertThat(fakeSettingsRepository.getPersistedSettings().gpuEnabled).isFalse()
        assertThat(fakeTranscriptionRepository.clearStateCalled).isTrue()
        coVerify(exactly = 0) {
            mockServiceLauncher.startFileTranscription(any(), any())
        }

        modelStateFlow.value = ModelManager.ModelState.Loading(model, useGpu = false)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            mockServiceLauncher.startFileTranscription(any(), any())
        }

        modelStateFlow.value = ModelManager.ModelState.Loaded(model, "Adreno 730")
        advanceUntilIdle()

        coVerify(exactly = 0) {
            mockServiceLauncher.startFileTranscription(any(), any())
        }

        modelStateFlow.value = ModelManager.ModelState.Loaded(model, gpuInfo = null)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            mockServiceLauncher.startFileTranscription(uri, "fr")
        }
    }

    @Test
    fun `GPU failure stops retrying when the CPU model fails to load`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loaded("ggml-small.bin", "Adreno 730")
        fakeTranscriptionRepository.setCurrentTranscriptionSource(
            TranscriptionSource.FileUri(mockk<Uri> { every { scheme } returns "content" })
        )

        fakeTranscriptionRepository.setState(
            TranscriptionState.Error(
                message = "Vulkan failed",
                recoverable = true,
                gpuWasEnabled = true
            )
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.transcriptionFailureReason)
            .isEqualTo(TranscriptionFailureReason.GPU_FAILURE_RETRYING)

        modelStateFlow.value = ModelManager.ModelState.Error(RuntimeException("no model"))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.transcriptionFailureReason)
            .isEqualTo(TranscriptionFailureReason.GENERIC_FAILURE)
        coVerify(exactly = 0) {
            mockServiceLauncher.startFileTranscription(any(), any())
        }
    }

    // =========================================================================
    // State Composition Tests
    // =========================================================================

    @Test
    fun `uiState combines model state and repo state`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", "Test GPU")
        fakeTranscriptionRepository.setState(TranscriptionState.Idle)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.screenState).isEqualTo(TranscriptionUiState.Ready)
            assertThat(state.canTranscribe).isTrue()
        }
    }

    @Test
    fun `canTranscribe is false when model not loaded`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.NotLoaded
        fakeTranscriptionRepository.setState(TranscriptionState.Idle)
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.canTranscribe).isFalse()
        }
    }

    @Test
    fun `canTranscribe is false when transcription in progress`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", "Test GPU")
        fakeTranscriptionRepository.setState(
            TranscriptionState.Transcribing(
                progress = 50,
                currentSegment = null,
                segments = emptyList()
            )
        )
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertThat(state.canTranscribe).isFalse()
        }
    }

    @Test
    fun `audio document name is resolved once while playback position changes`() = runTest {
        val uri = mockk<Uri> { every { scheme } returns "content" }
        coEvery { mockAudioListenUseCase.getFileNameFromUri(uri) } returns "meeting.opus"
        fakeTranscriptionRepository.setCurrentTranscriptionSource(
            TranscriptionSource.FileUri(uri)
        )
        fakeTranscriptionRepository.setState(
            TranscriptionState.Transcribing(
                progress = 10,
                currentSegment = null,
                segments = emptyList()
            )
        )
        advanceUntilIdle()

        repeat(5) { position ->
            playbackStateFlow.value = PlaybackState(
                isPlaying = true,
                isPrepared = true,
                currentPositionMs = position * 200L,
                durationMs = 1000L
            )
        }
        fakeTranscriptionRepository.setState(
            TranscriptionState.Transcribing(
                progress = 20,
                currentSegment = null,
                segments = emptyList()
            )
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.audioFileName).isEqualTo("meeting.opus")
        coVerify(exactly = 1) { mockAudioListenUseCase.getFileNameFromUri(uri) }
    }

    @Test
    fun `new audio URI does not wait for the previous display name`() = runTest {
        val firstUri = mockk<Uri> { every { scheme } returns "content" }
        val secondUri = mockk<Uri> { every { scheme } returns "content" }
        val firstLookupStarted = CompletableDeferred<Unit>()
        coEvery { mockAudioListenUseCase.getFileNameFromUri(firstUri) } coAnswers {
            firstLookupStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery {
            mockAudioListenUseCase.getFileNameFromUri(secondUri)
        } returns "second.opus"
        fakeTranscriptionRepository.setCurrentTranscriptionSource(
            TranscriptionSource.FileUri(firstUri)
        )
        fakeTranscriptionRepository.setState(
            TranscriptionState.Transcribing(
                progress = 10,
                currentSegment = null,
                segments = emptyList()
            )
        )
        runCurrent()
        firstLookupStarted.await()

        fakeTranscriptionRepository.setCurrentTranscriptionSource(
            TranscriptionSource.FileUri(secondUri)
        )
        fakeTranscriptionRepository.setState(
            TranscriptionState.Transcribing(
                progress = 20,
                currentSegment = null,
                segments = emptyList()
            )
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.audioUri).isEqualTo(secondUri)
        assertThat(viewModel.uiState.value.audioFileName).isEqualTo("second.opus")
    }

    @Test
    fun `unavailable file selection is saved before permission completes`() = runTest {
        val uri = mockk<Uri> { every { scheme } returns "content" }
        val permissionGate = CompletableDeferred<Unit>()
        coEvery { mockAudioListenUseCase.takePersistablePermission(uri) } coAnswers {
            permissionGate.await()
        }

        viewModel.handleSelectedFile(uri)

        assertThat(savedStateHandle.get<Uri>("pending_uri")).isEqualTo(uri)
        permissionGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `older permission result cannot replace a newer file selection`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", "Test GPU")
        fakeTranscriptionRepository.setState(TranscriptionState.Idle)
        advanceUntilIdle()
        val firstUri = mockk<Uri> { every { scheme } returns "content" }
        val secondUri = mockk<Uri> { every { scheme } returns "content" }
        val firstPermissionStarted = CompletableDeferred<Unit>()
        val firstPermissionGate = CompletableDeferred<Unit>()
        coEvery { mockAudioListenUseCase.takePersistablePermission(firstUri) } coAnswers {
            firstPermissionStarted.complete(Unit)
            firstPermissionGate.await()
        }
        coEvery { mockAudioListenUseCase.takePersistablePermission(secondUri) } just Runs

        viewModel.handleSelectedFile(firstUri)
        runCurrent()
        firstPermissionStarted.await()
        viewModel.handleSelectedFile(secondUri)
        advanceUntilIdle()
        firstPermissionGate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            mockServiceLauncher.startFileTranscription(firstUri, any())
        }
        coVerify(exactly = 1) {
            mockServiceLauncher.startFileTranscription(secondUri, any())
        }
    }

    // =========================================================================
    // Intent Handling Tests
    // =========================================================================

    @Test
    fun `handleIncomingIntent waits for model before starting transcription`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.NotLoaded
        val testUri = mockk<Uri> { every { scheme } returns "content" }
        val intent = mockk<Intent> {
            every { action } returns Intent.ACTION_SEND
            every { type } returns "audio/*"
            every { getStringExtra("language") } returns null
            every { getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM) } returns testUri
        }

        viewModel.handleIncomingIntent(intent)
        advanceUntilIdle()

        // URI saved but service not started yet
        assertThat(savedStateHandle.get<Uri>("pending_uri")).isEqualTo(testUri)

        // Now load the model
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", "Test GPU")
        advanceUntilIdle()

        // Now transcription should start
        coVerify { mockServiceLauncher.startFileTranscription(testUri, any()) }
    }

    @Test
    fun `handleIncomingIntent does not start transcription on model error`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Error(RuntimeException("Failed"))
        val testUri = mockk<Uri> { every { scheme } returns "content" }
        val intent = mockk<Intent> {
            every { action } returns Intent.ACTION_SEND
            every { type } returns "audio/*"
            every { getStringExtra("language") } returns null
            every { getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM) } returns testUri
        }

        viewModel.handleIncomingIntent(intent)
        advanceUntilIdle()

        // URI saved but service not started due to model error
        assertThat(savedStateHandle.get<Uri>("pending_uri")).isEqualTo(testUri)
        coVerify(exactly = 0) { mockServiceLauncher.startFileTranscription(any(), any()) }
    }

    @Test
    fun `shared links populate draft without downloading or asking for file permission`() = runTest {
        val intent = mockk<Intent> {
            every { action } returns Intent.ACTION_SEND
            every { type } returns "text/plain"
            every { getStringExtra("language") } returns null
            every { getStringExtra(Intent.EXTRA_TEXT) } returns "A video https://example.com/watch?v=1"
        }
        viewModel.handleIncomingIntent(intent)
        advanceUntilIdle()
        assertThat(viewModel.urlDraft.value).isEqualTo("A video https://example.com/watch?v=1")
        coVerify(exactly = 0) { mockServiceLauncher.startFileTranscription(any(), any()) }
        coVerify(exactly = 0) { mockAudioListenUseCase.takePersistablePermission(any()) }
    }

    @Test
    fun `invalid URL never launches foreground work`() = runTest {
        modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", null)
        advanceUntilIdle()
        viewModel.handleAction(MainScreenAction.EditUrl("file:///private/file"))
        viewModel.handleAction(MainScreenAction.TranscribeUrl)
        advanceUntilIdle()
        coVerify(exactly = 0) { mockServiceLauncher.startFileTranscription(any(), any()) }
    }

    @Test
    fun `URL draft survives view model recreation`() = runTest {
        viewModel.handleAction(MainScreenAction.EditUrl("https://example.com/audio.mp3"))
        createViewModel()
        assertThat(viewModel.urlDraft.value).isEqualTo("https://example.com/audio.mp3")
    }

    @Test
    fun `URL submission launches service without content permission or listen playback`() = runTest {
        val url = "https://example.com/audio.mp3"
        val uri = mockk<Uri> { every { scheme } returns "https" }
        mockkStatic(Uri::class)
        try {
            every { Uri.parse(url) } returns uri
            modelStateFlow.value = ModelManager.ModelState.Loaded("models/test.bin", null)
            advanceUntilIdle()
            viewModel.handleAction(MainScreenAction.EditUrl(url))
            viewModel.handleAction(MainScreenAction.TranscribeUrl)
            advanceUntilIdle()
            coVerify(exactly = 1) { mockServiceLauncher.startFileTranscription(uri, any()) }
            coVerify(exactly = 0) { mockAudioListenUseCase.takePersistablePermission(any()) }
            coVerify(exactly = 0) { mockAudioListenUseCase.prepareIfListenModeEnabled(any(), any()) }
        } finally {
            io.mockk.unmockkStatic(Uri::class)
        }
    }

}
