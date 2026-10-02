// SPDX-License-Identifier: GPL-3.0-or-later
package com.voiceskip.media

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

class UrlMediaDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** The caller owns the directory and removes it after decoding, failure or cancellation. */
    suspend fun download(url: String, directory: File, onProgress: (Int) -> Unit): File {
        require(MediaUrl.parse(url) == url) { "Enter one valid HTTP or HTTPS media link." }
        val processId = UUID.randomUUID().toString()
        try {
            return coroutineScope {
              val worker = async(Dispatchers.IO) {
                check(directory.mkdirs() || directory.isDirectory)
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--socket-timeout", 30)
                    addOption("--retries", 3)
                    addOption("--max-filesize", "500M")
                    addOption("--match-filter", "!is_live & duration <= 14400")
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                    addOption("--postprocessor-args", "ffmpeg:-ac 1 -ar 16000")
                    addOption("-o", File(directory, "audio.%(ext)s").absolutePath)
                }
                YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
                    onProgress(progress.toInt().coerceIn(0, 100))
                }
                File(directory, "audio.m4a").also {
                    if (!it.isFile || it.length() <= 44) throw IOException("No audio was found in this link.")
                }
              }
              try {
                  worker.await()
              } catch (e: CancellationException) {
                  // Kill the complete yt-dlp/FFmpeg process tree before allowing the
                  // caller to delete its files. Cancellation can race initialization
                  // or process registration, so keep checking until the worker exits.
                  withContext(NonCancellable + Dispatchers.IO) {
                      while (!worker.isCompleted) {
                          YoutubeDL.getInstance().destroyProcessById(processId)
                          delay(100)
                      }
                  }
                  throw e
              }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Do not expose signed URLs, cookies or extractor diagnostics in the UI/logs.
            throw IOException("Could not retrieve audio. Check your connection and use a public, non-live media link (up to 4 hours / 500 MB).")
        } finally {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
    }
}
