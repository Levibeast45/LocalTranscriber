// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.source

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AudioDocumentDataSourceTest {

    private lateinit var ioDispatcher: ExecutorCoroutineDispatcher
    private lateinit var cancellationDispatcher: ExecutorCoroutineDispatcher
    private lateinit var contentResolver: ContentResolver
    private lateinit var cancellationSignalFactory: ContentCancellationSignalFactory
    private lateinit var cancellationSignal: CancellationSignal
    private lateinit var descriptor: AssetFileDescriptor
    private lateinit var cursor: Cursor
    private lateinit var uri: Uri
    private lateinit var dataSource: AudioDocumentDataSourceImpl

    @Before
    fun setup() {
        ioDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "audio-document-io")
        }.asCoroutineDispatcher()
        cancellationDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "provider-cancellation-io")
        }.asCoroutineDispatcher()
        contentResolver = mockk()
        cancellationSignalFactory = mockk()
        cancellationSignal = mockk(relaxed = true)
        descriptor = mockk(relaxed = true)
        cursor = mockk(relaxed = true)
        uri = mockk(relaxed = true)

        every { cancellationSignalFactory.create() } returns cancellationSignal
        val context = mockk<Context> {
            every { contentResolver } returns this@AudioDocumentDataSourceTest.contentResolver
        }
        dataSource = AudioDocumentDataSourceImpl(
            context = context,
            ioDispatcher = ioDispatcher,
            cancellationDispatcher = cancellationDispatcher,
            cancellationSignalFactory = cancellationSignalFactory
        )
    }

    @After
    fun teardown() {
        ioDispatcher.close()
        cancellationDispatcher.close()
    }

    @Test
    fun `content resolver operations run on the IO dispatcher`() = runTest {
        val callThreads = mutableListOf<String>()
        every {
            contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal)
        } answers {
            callThreads += Thread.currentThread().name
            descriptor
        }
        every {
            contentResolver.query(
                uri,
                any<Array<String>>(),
                null,
                null,
                null,
                cancellationSignal
            )
        } answers {
            callThreads += Thread.currentThread().name
            cursor
        }
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.moveToFirst() } returns true
        every { cursor.getString(0) } returns "meeting.opus"
        every {
            contentResolver.takePersistableUriPermission(uri, any())
        } answers {
            callThreads += Thread.currentThread().name
            Unit
        }

        dataSource.withOpenAudio(uri) { Unit }
        assertThat(dataSource.getDisplayName(uri)).isEqualTo("meeting.opus")
        dataSource.takePersistableReadPermission(uri)

        assertThat(callThreads).hasSize(3)
        assertThat(callThreads).containsExactly(
            "audio-document-io",
            "audio-document-io",
            "audio-document-io"
        ).inOrder()
    }

    @Test
    fun `cancelling descriptor use closes it`() = runTest {
        every {
            contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal)
        } returns descriptor
        val blockEntered = CompletableDeferred<Unit>()

        val operation = launch {
            dataSource.withOpenAudio(uri) {
                blockEntered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        runCurrent()
        blockEntered.await()
        operation.cancelAndJoin()

        verify(exactly = 1) { descriptor.close() }
    }

    @Test
    fun `cancelling a blocked open signals the content provider`() = runTest {
        val providerCancelled = CountDownLatch(1)
        var cancellationThread: String? = null
        every { cancellationSignal.cancel() } answers {
            cancellationThread = Thread.currentThread().name
            providerCancelled.countDown()
        }
        val openStarted = CompletableDeferred<Unit>()
        every {
            contentResolver.openAssetFileDescriptor(uri, "r", cancellationSignal)
        } answers {
            openStarted.complete(Unit)
            if (!providerCancelled.await(2, TimeUnit.SECONDS)) {
                throw AssertionError("Content provider was not cancelled")
            }
            throw IOException("cancelled")
        }

        val operation = launch {
            dataSource.withOpenAudio(uri) { Unit }
        }
        runCurrent()
        openStarted.await()
        operation.cancelAndJoin()

        verify(exactly = 1) { cancellationSignal.cancel() }
        assertThat(cancellationThread).startsWith("provider-cancellation-io")
    }
}
