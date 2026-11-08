package com.careclose.core.sync.drive

import com.careclose.core.model.CareCloseJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

@Serializable
data class ServiceAccountCredentials(
    @SerialName("client_email") val clientEmail: String,
    @SerialName("private_key") val privateKeyPem: String,
    @SerialName("token_uri") val tokenUri: String = DEFAULT_TOKEN_URI,
) {
    companion object {
        const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
    }
}

@Serializable
private data class JwtHeader(
    val alg: String = "RS256",
    val typ: String = "JWT",
)

@Serializable
private data class JwtClaims(
    val iss: String,
    val scope: String,
    val aud: String,
    val iat: Long,
    val exp: Long,
)

object ServiceAccountJwtSigner {

    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

    fun parseCredentials(serviceAccountJson: String): ServiceAccountCredentials {
        return CareCloseJson.instance.decodeFromString(
            ServiceAccountCredentials.serializer(),
            serviceAccountJson,
        )
    }

    fun parseRsaPrivateKey(privateKeyPem: String): PrivateKey {
        val stripped = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s+"), "")
        val derBytes = Base64.getDecoder().decode(stripped)
        val spec = PKCS8EncodedKeySpec(derBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    fun createSignedAssertion(
        credentials: ServiceAccountCredentials,
        now: Instant = Instant.now(),
        scope: String = DRIVE_SCOPE,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val headerJson = CareCloseJson.instance.encodeToString(JwtHeader.serializer(), JwtHeader())

        val iat = now.epochSecond
        val exp = iat + 3600L
        val claims = JwtClaims(
            iss = credentials.clientEmail,
            scope = scope,
            aud = credentials.tokenUri,
            iat = iat,
            exp = exp,
        )
        val claimsJson = CareCloseJson.instance.encodeToString(JwtClaims.serializer(), claims)

        val signingInput = "${encoder.encodeToString(headerJson.toByteArray(Charsets.UTF_8))}." +
            encoder.encodeToString(claimsJson.toByteArray(Charsets.UTF_8))

        val privateKey = parseRsaPrivateKey(credentials.privateKeyPem)
        val signatureBytes = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
            sign()
        }

        return "$signingInput.${encoder.encodeToString(signatureBytes)}"
    }
}

