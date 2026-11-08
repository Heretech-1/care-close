package com.careclose.core.sync.pairing

import com.careclose.core.model.CareCloseJson
import com.careclose.core.model.PairingQrPayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object PairingPayloadCodec {

    private const val QR_PREFIX = "CARECLOSE1:"

    fun encode(payload: PairingQrPayload): String {
        val jsonBytes = CareCloseJson.instance
            .encodeToString(PairingQrPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)

        val compressed = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gzip ->
                gzip.write(jsonBytes)
            }
            baos.toByteArray()
        }

        return QR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }

    fun decode(qrContent: String): PairingQrPayload {
        val trimmed = qrContent.trim()
        require(trimmed.startsWith(QR_PREFIX)) { "Невідомий формат QR-коду CareClose" }
        val encodedBody = trimmed.removePrefix(QR_PREFIX)
        val compressed = Base64.getUrlDecoder().decode(encodedBody)

        val jsonBytes = GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
            gzip.readBytes()
        }

        return CareCloseJson.instance.decodeFromString(
            PairingQrPayload.serializer(),
            jsonBytes.toString(Charsets.UTF_8),
        )
    }
}

