package com.kliniq.infra.webauthn

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires up WebAuthn4J's stateless helpers as Spring beans. Both the
 * [WebAuthnManager] facade (registration + authentication ceremonies) and
 * the converters are thread-safe and expensive to construct once you go
 * deep into Jackson + Bouncy Castle, so we hand the same instance to every
 * caller.
 *
 * We use `createNonStrictWebAuthnManager()` because we don't gate sign-in
 * on attestation — most consumer authenticators ship "none" attestation,
 * and verifying device provenance against FIDO MDS adds maintenance cost
 * (cert chains, MDS refresh) without giving us extra security in the
 * passkey-as-second-factor model. If a clinic deployment ever requires
 * enterprise attestation we can swap this for `createWebAuthnManager()`
 * with an MDS-backed validator.
 */
@Configuration
class WebAuthnConfig {
    @Bean
    fun objectConverter(): ObjectConverter = ObjectConverter()

    @Bean
    fun webAuthnManager(): WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager()

    @Bean
    fun attestedCredentialDataConverter(objectConverter: ObjectConverter): AttestedCredentialDataConverter =
        AttestedCredentialDataConverter(objectConverter)
}
