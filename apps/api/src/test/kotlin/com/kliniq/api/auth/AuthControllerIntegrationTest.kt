package com.kliniq.api.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.kliniq.db.tables.references.EMAIL_VERIFICATION_TOKENS
import com.kliniq.db.tables.references.USERS
import com.kliniq.infra.mail.EmailSender
import com.kliniq.support.TestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@Import(TestcontainersConfig::class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
        private val dsl: DSLContext,
    ) {
        @MockitoBean
        private lateinit var emailSender: EmailSender

        @BeforeEach
        fun reset() {
            // Tokens cascade-delete via FK ON DELETE CASCADE when users are dropped.
            dsl.deleteFrom(EMAIL_VERIFICATION_TOKENS).execute()
            dsl.deleteFrom(USERS).execute()
        }

        private fun registerBody(
            email: String,
            password: String = "correct horse battery staple",
            displayName: String = "Test User",
        ): String =
            objectMapper.writeValueAsString(
                mapOf(
                    "email" to email,
                    "password" to password,
                    "displayName" to displayName,
                ),
            )

        @Test
        fun `register creates a user and dispatches a verification email`() {
            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "alice@kliniq.local", displayName = "Alice")
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.message") { exists() }
                }

            assertThat(
                dsl.fetchExists(
                    dsl
                        .selectOne()
                        .from(USERS)
                        .where(USERS.EMAIL_NORMALIZED.eq("alice@kliniq.local")),
                ),
            ).isTrue()

            verify(emailSender, times(1)).sendEmailVerification(
                eq("alice@kliniq.local"),
                eq("Alice"),
                argThat { url -> url.contains("token=") && url.startsWith("https://") },
            )
        }

        @Test
        fun `register with an existing email returns success without recreating the user`() {
            val body = registerBody(email = "bob@kliniq.local", displayName = "Bob")

            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isOk() } }

            // Same email again — still 200, neutral.
            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isOk() } }

            val count =
                dsl
                    .selectCount()
                    .from(USERS)
                    .where(USERS.EMAIL_NORMALIZED.eq("bob@kliniq.local"))
                    .fetchOne(0, Int::class.java) ?: 0
            assertThat(count).isEqualTo(1)

            // Email was sent only on the first registration.
            verify(emailSender, times(1)).sendEmailVerification(any(), any(), any())
        }

        @Test
        fun `register with too-short password returns 400 with VALIDATION_ERROR`() {
            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "carol@kliniq.local", password = "short")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("VALIDATION_ERROR") }
                    jsonPath("$.fieldErrors[*].field") { value("password") }
                }

            assertThat(
                dsl.fetchExists(
                    dsl
                        .selectOne()
                        .from(USERS)
                        .where(USERS.EMAIL_NORMALIZED.eq("carol@kliniq.local")),
                ),
            ).isFalse()

            verify(emailSender, never()).sendEmailVerification(any(), any(), any())
        }

        @Test
        fun `register with malformed email returns 400`() {
            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content = registerBody(email = "not-an-email")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("VALIDATION_ERROR") }
                }
        }
    }
