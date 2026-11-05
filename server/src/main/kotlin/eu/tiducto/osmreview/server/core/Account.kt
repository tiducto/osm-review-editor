package eu.tiducto.osmreview.server.core

import eu.tiducto.osmreview.server.Config
import eu.tiducto.osmreview.server.db.SettingsRepo
import eu.tiducto.osmreview.server.osm.OsmApi
import eu.tiducto.osmreview.shared.AccountStatus
import eu.tiducto.osmreview.shared.OsmTarget
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The OSM account and API target. Dev is the default target until the user switches to production
 * (which needs an explicit confirm). Each target has its own OAuth 2.0 app and token; tokens are
 * stored AES-GCM-encrypted with TOKEN_ENCRYPTION_KEY, or kept in memory only when no key is set.
 */
class Account(
    private val config: Config,
    private val settings: SettingsRepo,
    private val http: HttpClient,
) {
    private val log = LoggerFactory.getLogger(Account::class.java)
    private val crypto = config.tokenEncryptionKey?.let(::TokenCrypto)
    private val memoryTokens = ConcurrentHashMap<OsmTarget, String>()
    private val pending = ConcurrentHashMap<String, PendingAuth>()
    private val random = SecureRandom()

    private data class PendingAuth(val target: OsmTarget, val verifier: String, val createdAt: Long)

    @Volatile
    var target: OsmTarget = settings.get(TARGET_KEY)?.let { if (it == "prod") OsmTarget.PROD else OsmTarget.DEV }
        ?: config.initialTarget
        private set

    fun setTarget(t: OsmTarget) {
        target = t
        settings.put(TARGET_KEY, if (t == OsmTarget.PROD) "prod" else "dev")
    }

    fun api(t: OsmTarget = target): OsmApi = OsmApi(http, config.target(t), config.userAgent)

    fun token(t: OsmTarget = target): String? =
        memoryTokens[t] ?: settings.get(tokenKey(t))?.let { enc -> crypto?.decrypt(enc) }

    fun requireToken(): String = token() ?: throw NotAuthorizedException(
        "Connect an OSM account for the ${target.name.lowercase()} API in Settings first",
    )

    fun status(): AccountStatus = AccountStatus(
        target = target,
        connected = token() != null,
        displayName = settings.get(nameKey(target)),
        configured = config.target(target).isConfigured,
    )

    val tokensPersisted: Boolean get() = crypto != null

    // ---- OAuth 2.0 authorization code + PKCE ----

    private val redirectUri get() = "${config.publicUrl}/auth/callback"

    fun authorizeUrl(): String {
        val t = config.target(target)
        val clientId = t.clientId ?: throw IllegalStateException(
            "No OAuth app for the ${target.name.lowercase()} API: set OSM_CLIENT_ID_${target.name} in .env",
        )
        pending.entries.removeIf { System.currentTimeMillis() - it.value.createdAt > PENDING_TTL_MS }
        val verifier = randomToken(48)
        val state = randomToken(24)
        pending[state] = PendingAuth(target, verifier, System.currentTimeMillis())
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return "${t.webUrl}/oauth2/authorize?response_type=code" +
            "&client_id=${clientId.encodeURLParameter()}" +
            "&redirect_uri=${redirectUri.encodeURLParameter()}" +
            "&scope=${"read_prefs write_api".encodeURLParameter()}" +
            "&state=$state&code_challenge=$challenge&code_challenge_method=S256"
    }

    suspend fun completeAuth(code: String, state: String) {
        val p = pending.remove(state) ?: throw IllegalArgumentException("Unknown or expired OAuth state; start again")
        val t = config.target(p.target)
        val resp = http.submitForm(
            url = "${t.webUrl}/oauth2/token",
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("client_id", t.clientId!!)
                t.clientSecret?.let { append("client_secret", it) }
                append("code_verifier", p.verifier)
            },
        )
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw IllegalStateException("OAuth token exchange failed: ${resp.status} ${body.take(200)}")
        val token = Json.parseToJsonElement(body).jsonObject["access_token"]!!.jsonPrimitive.content
        val user = api(p.target).userDetails(token)
        store(p.target, token)
        settings.put(nameKey(p.target), user.displayName)
        log.info("Connected OSM account {} on {}", user.displayName, p.target)
    }

    fun logout() {
        memoryTokens.remove(target)
        settings.delete(tokenKey(target))
        settings.delete(nameKey(target))
    }

    private fun store(t: OsmTarget, token: String) {
        if (crypto != null) {
            settings.put(tokenKey(t), crypto.encrypt(token))
        } else {
            log.warn("TOKEN_ENCRYPTION_KEY is not set: the OSM token is kept in memory only and lost on restart")
            memoryTokens[t] = token
        }
    }

    private fun randomToken(bytes: Int) = b64(ByteArray(bytes).also(random::nextBytes))

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun tokenKey(t: OsmTarget) = "oauth.token.${t.name.lowercase()}"
    private fun nameKey(t: OsmTarget) = "oauth.name.${t.name.lowercase()}"

    private companion object {
        const val TARGET_KEY = "osm.target"
        const val PENDING_TTL_MS = 15 * 60 * 1000L
    }
}

class NotAuthorizedException(message: String) : RuntimeException(message)

/** AES-256-GCM with a key derived from the configured secret (SHA-256). Output: base64(iv || ciphertext). */
class TokenCrypto(secret: String) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()), "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    fun decrypt(enc: String): String? = runCatching {
        val all = Base64.getDecoder().decode(enc)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, all, 0, 12))
        }
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()
}
