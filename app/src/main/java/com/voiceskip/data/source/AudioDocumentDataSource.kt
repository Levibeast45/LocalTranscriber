// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip.data.source

import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.voiceskip.di.IoDispatcher
import com.voiceskip.di.ProviderCancellationDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Singleton

interface AudioDocumentDataSource {
    suspend fun <T> withOpenAudio(
        uri: Uri,
        block: suspend (AssetFileDescriptor) -> T
    ): T

    suspend fun getDisplayName(uri: Uri): String?
    suspend fun takePersistableReadPermission(uri: Uri)
}

class ContentCancellationSignalFactory @Inject constructor() {
    fun create(): CancellationSignal = CancellationSignal()
}

@Singleton
class AudioDocumentDataSourceImpl @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ProviderCancellationDispatcher cancellationDispatcher: CoroutineDispatcher,
    private val cancellationSignalFactory: ContentCancellationSignalFactory
) : AudioDocumentDataSource {

    private val contentResolver = context.contentResolver
    private val providerCancellationScope = CoroutineScope(
        SupervisorJob() + cancellationDispatcher
    )

    override suspend fun <T> withOpenAudio(
        uri: Uri,
        block: suspend (AssetFileDescriptor) -> T
    ): T = withContext(ioDispatcher) {
        openAudio(uri).use { descriptor -> block(descriptor) }
    }

    override suspend fun getDisplayName(uri: Uri): String? = withContext(ioDispatcher) {
        try {
            queryDisplayName(uri)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            null
        }
    }

    override suspend fun takePersistableReadPermission(uri: Uri) = withContext(ioDispatcher) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (exception: SecurityException) {
            Unit
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun openAudio(uri: Uri): AssetFileDescriptor =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = cancellationSignalFactory.create()
            continuation.invokeOnCancellation { cancelProviderOperation(cancellationSignal) }

            try {
                val descriptor = contentResolver.openAssetFileDescriptor(
                    uri,
                    "r",
                    cancellationSignal
                ) ?: throw FileNotFoundException("Unable to open audio URI: $uri")

                continuation.resume(descriptor) {
                    runCatching { descriptor.close() }
                }
            } catch (exception: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }
        }

    private suspend fun queryDisplayName(uri: Uri): String? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = cancellationSignalFactory.create()
            continuation.invokeOnCancellation { cancelProviderOperation(cancellationSignal) }

            try {
                val displayName = contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                    cancellationSignal
                )?.use { cursor ->
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && cursor.moveToFirst()) {
                        cursor.getString(nameColumn)
                    } else {
                        null
                    }
                }
                continuation.resume(displayName)
            } catch (exception: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(exception)
                }
            }
        }

    private fun cancelProviderOperation(cancellationSignal: CancellationSignal) {
        providerCancellationScope.launch {
            runCatching { cancellationSignal.cancel() }
        }
    }
}
