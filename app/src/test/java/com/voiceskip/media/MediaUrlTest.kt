// SPDX-License-Identifier: GPL-3.0-or-later
package com.voiceskip.media

import org.junit.Assert.*
import org.junit.Test

class MediaUrlTest {
    @Test fun `extracts link from shared title and preserves query`() {
        assertEquals("https://youtu.be/abc?t=20&feature=shared",
            MediaUrl.parse("A video\nhttps://youtu.be/abc?t=20&feature=shared"))
    }
    @Test fun `rejects unsupported or ambiguous input`() {
        listOf("", "file:///sdcard/audio.mp3", "content://media/1", "javascript:alert(1)",
            "https://", "https://user:password@example.com/a", "https://a.com https://b.com")
            .forEach { assertNull(it, MediaUrl.parse(it)) }
    }
    @Test fun `accepts direct media and surrounding punctuation`() {
        assertEquals("https://example.com/audio.mp3", MediaUrl.parse("Listen (https://example.com/audio.mp3)."))
        assertEquals("http://example.com/audio.mp3", MediaUrl.parse("http://example.com/audio.mp3"))
    }
}
