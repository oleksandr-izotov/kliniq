package com.kliniq.api.passkey

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.PASSKEYS
import com.kliniq.db.tables.references.USERS
import com.kliniq.infra.mail.EmailSender
import com.kliniq.infra.security.SessionCookieService
import com.kliniq.support.TestcontainersConfig
import com.webauthn4j.data.AttestationConveyancePreference
import com.webauthn4j.data.AuthenticatorAssertionResponse
import com.webauthn4j.data.AuthenticatorAttestationResponse
import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialDescriptor
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.test.authenticator.webauthn.PackedAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.Base64

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasskeyControllerIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val mapper: ObjectMapper,
        private val dsl: DSLContext,
        private val redis: StringRedisTemplate,
    ) {
        @MockitoBean
        private lateinit var emailSender: EmailSender

        @BeforeEach
        fun reset() {
            Mockito.reset(emailSender)
            // FK ON DELETE CASCADE clears passkeys when users go, but bookings +
            // operating_rooms reference users with ON DELETE RESTRICT — drop them
            // first when a prior test class left rows behind.
            dsl.deleteFrom(com.kliniq.db.tables.references.BOOKINGS).execute()
            dsl.deleteFrom(com.kliniq.db.tables.references.OPERATING_ROOMS).execute()
            dsl.deleteFrom(PASSKEYS).execute()
            dsl.deleteFrom(USERS).execute()
            redis.keys("rate-limit:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            redis.keys("kliniq:passkey-*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
            redis.keys("kliniq:session:*")?.takeIf { it.isNotEmpty() }?.let(redis::delete)
        }

        // ---------------------------------------------------------------------
        // Helpers — register + verify + login a user, return session cookie.
        // Mirrors AuthControllerIntegrationTest's helpers.
        // ---------------------------------------------------------------------

        private fun registerVerifyAndLogin(
            email: String = "alice@kliniq.local",
            password: String = "correct-horse-battery-staple",
        ): String {
            // /register
            mockMvc
                .post("/api/v1/auth/register") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        mapper.writeValueAsString(
                            mapOf("email" to email, "password" to password, "displayName" to "Alice"),
                        )
                }.andExpect { status { isOk() } }

            val token = captureVerifyToken()

            mockMvc
                .post("/api/v1/auth/verify") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("token" to token))
                }.andExpect { status { isOk() } }

            val loginResult: MvcResult =
                mockMvc
                    .post("/api/v1/auth/login") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("email" to email, "password" to password))
                    }.andExpect { status { isOk() } }
                    .andReturn()

            return checkNotNull(loginResult.sessionCookieValue()) {
                "/login did not set a __Host-kliniq_session cookie"
            }
        }

        private fun captureVerifyToken(): String {
            val captor = org.mockito.kotlin.argumentCaptor<String>()
            org.mockito.kotlin
                .verify(emailSender)
                .sendEmailVerification(org.mockito.kotlin.any(), org.mockito.kotlin.any(), captor.capture())
            return captor.lastValue.substringAfter("token=")
        }

        private fun MvcResult.sessionCookieValue(): String? =
            response
                .getHeader("Set-Cookie")
                ?.takeIf { it.startsWith("${SessionCookieService.COOKIE_NAME}=") }
                ?.substringAfter("=")
                ?.substringBefore(";")

        // ---------------------------------------------------------------------
        // Virtual authenticator helpers
        // ---------------------------------------------------------------------

        /** Fresh virtual platform per test so credentials don't leak between cases. */
        private fun newClientPlatform(): ClientPlatform =
            ClientPlatform(Origin.create(TEST_ORIGIN), WebAuthnAuthenticatorAdaptor(PackedAuthenticator()))

        /**
         * Drive the registration ceremony end-to-end against the running app.
         * Returns the persisted PasskeySummary JSON (id, deviceName, createdAt).
         */
        private fun registerPasskey(
            sessionCookie: String,
            clientPlatform: ClientPlatform,
            deviceName: String = "TestKey",
        ): JsonNode {
            // /registration/begin
            val beginJson =
                mockMvc
                    .post("/api/v1/auth/passkeys/registration/begin") {
                        with(csrf())
                        cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, sessionCookie))
                        contentType = MediaType.APPLICATION_JSON
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
                    .let(mapper::readTree)

            // Reconstruct the WebAuthn4J creation options from the wire JSON.
            val challenge = base64urlDecode(beginJson["challenge"].asText())
            val userHandle = base64urlDecode(beginJson["user"]["id"].asText())
            val rp = PublicKeyCredentialRpEntity(beginJson["rp"]["id"].asText(), beginJson["rp"]["name"].asText())
            val user =
                PublicKeyCredentialUserEntity(
                    userHandle,
                    beginJson["user"]["name"].asText(),
                    beginJson["user"]["displayName"].asText(),
                )

            val creationOptions =
                PublicKeyCredentialCreationOptions(
                    rp,
                    user,
                    DefaultChallenge(challenge),
                    listOf(
                        PublicKeyCredentialParameters(
                            PublicKeyCredentialType.PUBLIC_KEY,
                            COSEAlgorithmIdentifier.ES256,
                        ),
                    ),
                    beginJson["timeout"].asLong(),
                    // excludeCredentials =
                    null,
                    AuthenticatorSelectionCriteria(null, false, UserVerificationRequirement.PREFERRED),
                    AttestationConveyancePreference.NONE,
                    // extensions =
                    null,
                )

            val credential = clientPlatform.create(creationOptions)
            val response: AuthenticatorAttestationResponse = checkNotNull(credential.response)

            val finishBody =
                mapper.writeValueAsString(
                    mapOf(
                        "id" to base64urlEncode(credential.rawId),
                        "attestationObject" to base64urlEncode(response.attestationObject),
                        "clientDataJSON" to base64urlEncode(response.clientDataJSON),
                        "deviceName" to deviceName,
                    ),
                )

            return mockMvc
                .post("/api/v1/auth/passkeys/registration/finish") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, sessionCookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = finishBody
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString
                .let(mapper::readTree)
        }

        // ---------------------------------------------------------------------
        // Tests
        // ---------------------------------------------------------------------

        @Test
        fun `registration round-trip persists a passkey for the authenticated user`() {
            val cookie = registerVerifyAndLogin()
            val client = newClientPlatform()

            val summary = registerPasskey(cookie, client, deviceName = "MacBook")

            assertThat(summary["id"].asText()).isNotBlank()
            assertThat(summary["deviceName"].asText()).isEqualTo("MacBook")

            // DB-side: exactly one row, owned by the user.
            assertThat(dsl.fetchCount(PASSKEYS)).isEqualTo(1)
        }

        @Test
        fun `registration finish without a prior begin returns 400`() {
            val cookie = registerVerifyAndLogin()

            val finishBody =
                mapper.writeValueAsString(
                    mapOf(
                        "id" to base64urlEncode(byteArrayOf(0, 1, 2)),
                        "attestationObject" to base64urlEncode(byteArrayOf(0, 0)),
                        "clientDataJSON" to base64urlEncode(byteArrayOf(0, 0)),
                        "deviceName" to "Spoof",
                    ),
                )

            mockMvc
                .post("/api/v1/auth/passkeys/registration/finish") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = finishBody
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("PASSKEY_CHALLENGE_EXPIRED") }
                }
        }

        @Test
        @Suppress("LongMethod") // ceremony has many request/response steps; one body keeps them readable
        fun `authentication round-trip mints a session for the credential owner`() {
            val cookie = registerVerifyAndLogin()
            val client = newClientPlatform()
            registerPasskey(cookie, client, deviceName = "MacBook")

            // /authentication/begin (anonymous)
            val beginJson =
                mockMvc
                    .post("/api/v1/auth/passkeys/authentication/begin") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("email" to "alice@kliniq.local"))
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
                    .let(mapper::readTree)

            val handle = beginJson["handle"].asText()
            val challenge = base64urlDecode(beginJson["challenge"].asText())
            val rpId = beginJson["rpId"].asText()

            // Pass the server-supplied allowCredentials to the virtual
            // authenticator so it can find the credential we registered
            // (PackedAuthenticator doesn't index non-resident keys by rpId
            // alone — they're scoped by credential id descriptor).
            val allowCredentials =
                beginJson["allowCredentials"].mapNotNull { node ->
                    PublicKeyCredentialDescriptor(
                        PublicKeyCredentialType.PUBLIC_KEY,
                        base64urlDecode(node["id"].asText()),
                        null,
                    )
                }

            val requestOptions =
                PublicKeyCredentialRequestOptions(
                    DefaultChallenge(challenge),
                    beginJson["timeout"].asLong(),
                    rpId,
                    allowCredentials,
                    UserVerificationRequirement.PREFERRED,
                    // extensions =
                    null,
                )

            val assertionCredential = client.get(requestOptions)
            val assertionResponse: AuthenticatorAssertionResponse = checkNotNull(assertionCredential.response)

            val finishBody =
                mapper.writeValueAsString(
                    mapOf(
                        "handle" to handle,
                        "id" to base64urlEncode(assertionCredential.rawId),
                        "authenticatorData" to base64urlEncode(assertionResponse.authenticatorData),
                        "clientDataJSON" to base64urlEncode(assertionResponse.clientDataJSON),
                        "signature" to base64urlEncode(assertionResponse.signature),
                        "userHandle" to assertionResponse.userHandle?.let { base64urlEncode(it) },
                    ),
                )

            val finishResult =
                mockMvc
                    .post("/api/v1/auth/passkeys/authentication/finish") {
                        with(csrf())
                        contentType = MediaType.APPLICATION_JSON
                        content = finishBody
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.email") { value("alice@kliniq.local") }
                    }.andReturn()

            // A session cookie must have been written.
            val newCookie = finishResult.sessionCookieValue()
            assertThat(newCookie).isNotBlank()
            // And that cookie must let us hit /me.
            mockMvc
                .get("/api/v1/auth/me") {
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, newCookie!!))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.email") { value("alice@kliniq.local") }
                }
        }

        @Test
        fun `authentication finish with a stale handle returns 400`() {
            val cookie = registerVerifyAndLogin()
            val client = newClientPlatform()
            registerPasskey(cookie, client)

            // /authentication/begin (so we have a valid credential reachable),
            // then deliberately use a bogus handle on /finish.
            val finishBody =
                mapper.writeValueAsString(
                    mapOf(
                        "handle" to "deadbeef-handle-that-was-never-issued",
                        "id" to base64urlEncode(byteArrayOf(0, 1, 2)),
                        "authenticatorData" to base64urlEncode(byteArrayOf(0)),
                        "clientDataJSON" to base64urlEncode(byteArrayOf(0)),
                        "signature" to base64urlEncode(byteArrayOf(0)),
                        "userHandle" to null,
                    ),
                )

            mockMvc
                .post("/api/v1/auth/passkeys/authentication/finish") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = finishBody
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("PASSKEY_CHALLENGE_EXPIRED") }
                }
        }

        @Test
        fun `list returns persisted passkeys for the authenticated user only`() {
            val cookie = registerVerifyAndLogin()
            val client = newClientPlatform()
            registerPasskey(cookie, client, deviceName = "Phone")
            registerPasskey(cookie, client, deviceName = "Laptop")

            mockMvc
                .get("/api/v1/auth/passkeys") {
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.length()") { value(2) }
                    // Newest first per repository ordering.
                    jsonPath("$[0].deviceName") { value("Laptop") }
                    jsonPath("$[1].deviceName") { value("Phone") }
                }
        }

        @Test
        fun `rename and revoke endpoints update the row owned by the user`() {
            val cookie = registerVerifyAndLogin()
            val client = newClientPlatform()
            val summary = registerPasskey(cookie, client, deviceName = "Original")
            val passkeyId = summary["id"].asText()

            // PATCH rename
            mockMvc
                .patch("/api/v1/auth/passkeys/$passkeyId") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("deviceName" to "Renamed"))
                }.andExpect { status { isNoContent() } }

            mockMvc
                .get("/api/v1/auth/passkeys") {
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                }.andExpect {
                    jsonPath("$[0].deviceName") { value("Renamed") }
                }

            // DELETE revoke
            mockMvc
                .delete("/api/v1/auth/passkeys/$passkeyId") {
                    with(csrf())
                    cookie(jakarta.servlet.http.Cookie(SessionCookieService.COOKIE_NAME, cookie))
                }.andExpect { status { isNoContent() } }

            assertThat(dsl.fetchCount(PASSKEYS)).isZero()
        }

        @Test
        fun `unauthenticated registration begin is rejected`() {
            mockMvc
                .post("/api/v1/auth/passkeys/registration/begin") {
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                }.andExpect { status { isUnauthorized() } }
        }

        // ---------------------------------------------------------------------
        // Base64url helpers
        // ---------------------------------------------------------------------

        private fun base64urlEncode(bytes: ByteArray): String = ENCODER.encodeToString(bytes)

        private fun base64urlDecode(s: String): ByteArray = DECODER.decode(s)

        companion object {
            // application-test.yml lists this as the only allowed origin.
            private const val TEST_ORIGIN = "http://localhost"
            private val ENCODER = Base64.getUrlEncoder().withoutPadding()
            private val DECODER = Base64.getUrlDecoder()
        }
    }
