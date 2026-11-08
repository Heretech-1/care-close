package com.careclose.feature.onboarding.qr

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleDriveFolderIdParserTest {

    @Test
    fun `extracts id from full share url and plain id`() {
        assertEquals(
            "1AbCdEfGhIjKlMnOpQrStUvWxYz",
            GoogleDriveFolderIdParser.extractFolderId(
                "https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOpQrStUvWxYz?usp=sharing",
            ),
        )
        assertEquals(
            "1XyZ987_abc-123",
            GoogleDriveFolderIdParser.extractFolderId(
                "https://drive.google.com/drive/u/0/folders/1XyZ987_abc-123",
            ),
        )
        assertEquals(
            "1PlainFolderId",
            GoogleDriveFolderIdParser.extractFolderId("  1PlainFolderId  "),
        )
    }
}

