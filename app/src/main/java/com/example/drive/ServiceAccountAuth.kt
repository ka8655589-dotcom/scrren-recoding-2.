package com.example.drive

import android.util.Base64
import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

object ServiceAccountAuth {

    private const val TAG = "ServiceAccountAuth"
    private const val TOKEN_URI = "https://oauth2.googleapis.com/token"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // In-memory cache for the token
    private var cachedToken: String? = null
    private var tokenExpiryTimeMs: Long = 0

    /**
     * Obtains an active OAuth access token using Service Account credentials.
     * Caches the token and automatically refreshes it when near expiration.
     */
    fun getFreshAccessToken(serviceAccountJson: String): Pair<String?, String?> {
        val trimmedJson = serviceAccountJson.trim()
        if (trimmedJson.isEmpty()) {
            return Pair(null, "Service Account JSON is empty")
        }

        // Return cached token if valid for at least 5 more minutes
        if (!cachedToken.isNullOrBlank() && System.currentTimeMillis() < tokenExpiryTimeMs - 300_000) {
            return Pair(cachedToken, null)
        }

        return try {
            val json = JSONObject(trimmedJson)
            val clientEmail = json.optString("client_email", "")
            val privateKeyRaw = json.optString("private_key", "")

            if (clientEmail.isBlank() || privateKeyRaw.isBlank()) {
                return Pair(null, "Invalid JSON: missing 'client_email' or 'private_key'")
            }

            val jwt = createSignedJwt(clientEmail, privateKeyRaw)
            val requestBody = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("assertion", jwt)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URI)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val respJson = JSONObject(responseBody)
                val token = respJson.optString("access_token")
                val expiresInSec = respJson.optLong("expires_in", 3600)

                cachedToken = token
                tokenExpiryTimeMs = System.currentTimeMillis() + (expiresInSec * 1000)
                Log.i(TAG, "Successfully exchanged Service Account JWT for Google Access Token!")
                Pair(token, null)
            } else {
                val errMsg = "Token exchange failed (${response.code}): $responseBody"
                Log.e(TAG, errMsg)
                Pair(null, errMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error authenticating Service Account: ${e.message}", e)
            Pair(null, e.localizedMessage ?: "Unknown authentication error")
        }
    }

    private fun createSignedJwt(clientEmail: String, privateKeyPem: String): String {
        // Strip PEM headers and whitespace
        val cleanKey = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")

        val keyBytes = Base64.decode(cleanKey, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("RSA")
        val privateKey = kf.generatePrivate(spec)

        val headerJson = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
        }.toString()

        val nowSec = System.currentTimeMillis() / 1000
        val claimJson = JSONObject().apply {
            put("iss", clientEmail)
            put("scope", "https://www.googleapis.com/auth/drive")
            put("aud", TOKEN_URI)
            put("exp", nowSec + 3600)
            put("iat", nowSec)
        }.toString()

        val headerEncoded = Base64.encodeToString(
            headerJson.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val claimEncoded = Base64.encodeToString(
            claimJson.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        val dataToSign = "$headerEncoded.$claimEncoded"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(dataToSign.toByteArray(Charsets.UTF_8))
        val signatureBytes = signer.sign()

        val signatureEncoded = Base64.encodeToString(
            signatureBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        return "$dataToSign.$signatureEncoded"
    }
}
