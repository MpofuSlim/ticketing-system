package com.innbucks.userservice.devicesecurity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The login ticket (contract §3 rule 3): RS256, two minutes, naming the number,
 * the device and the purpose — and verifiable by the broker from the published
 * JWKS alone.
 */
class LoginTicketSignerTest {

    private static final Instant T0 = Instant.parse("2026-09-29T09:58:12Z");

    private static LoginTicketSigner signer(Clock clock, String pem) {
        DeviceSecurityProperties.Ticket t = new DeviceSecurityProperties.Ticket();
        t.setPrivateKey(pem);
        return new LoginTicketSigner(t, clock);
    }

    @Test
    @DisplayName("a ticket round-trips with the contract's claims")
    void ticket_roundTrips() {
        LoginTicketSigner s = signer(Clock.fixed(T0, ZoneOffset.UTC), TestKeys.privatePem());
        String jws = s.signTicket("tkt_abc", "+263771234512", "8b1e-install", SignInPurpose.SIGN_IN, T0.plusSeconds(120));

        Claims c = s.verifyTicket(jws);
        assertThat(c.getId()).isEqualTo("tkt_abc");
        assertThat(c.getSubject()).isEqualTo("+263771234512");
        assertThat(c.get("msisdn", String.class)).isEqualTo("+263771234512");
        assertThat(c.get("installId", String.class)).isEqualTo("8b1e-install");
        assertThat(c.get("purpose", String.class)).isEqualTo("SIGN_IN");
        assertThat(c.getIssuer()).isEqualTo("innbucks-dtx");
        assertThat(c.getAudience()).containsExactly("innbucks-broker");
    }

    @Test
    @DisplayName("the broker can verify with nothing but the JWKS")
    void jwks_verifiesTickets() throws Exception {
        LoginTicketSigner s = signer(Clock.fixed(T0, ZoneOffset.UTC), TestKeys.privatePem());
        String jws = s.signTicket("tkt_abc", "+263771234512", "dev", SignInPurpose.SIGN_IN, T0.plusSeconds(120));

        @SuppressWarnings("unchecked")
        Map<String, Object> jwk = ((List<Map<String, Object>>) s.jwks().get("keys")).get(0);
        assertThat(jwk).containsEntry("kty", "RSA").containsEntry("alg", "RS256").containsEntry("kid", "dtx-ticket-1");
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("n"))),
                new BigInteger(1, Base64.getUrlDecoder().decode((String) jwk.get("e")))));
        Claims c = Jwts.parser().verifyWith(key).clock(() -> java.util.Date.from(T0)).build()
                .parseSignedClaims(jws).getPayload();
        assertThat(c.getId()).isEqualTo("tkt_abc");
    }

    @Test
    void expiredTicket_isRefusedAsExpired() {
        LoginTicketSigner mint = signer(Clock.fixed(T0, ZoneOffset.UTC), TestKeys.privatePem());
        String jws = mint.signTicket("tkt_abc", "+263771234512", "dev", SignInPurpose.SIGN_IN, T0.plusSeconds(120));
        LoginTicketSigner later = signer(Clock.fixed(T0.plusSeconds(200), ZoneOffset.UTC), TestKeys.privatePem());
        assertThatThrownBy(() -> later.verifyTicket(jws)).isInstanceOf(LoginTicketSigner.TicketExpiredException.class);
    }

    @Test
    void ticketSignedWithAnotherKey_isRefused() {
        LoginTicketSigner other = signer(Clock.fixed(T0, ZoneOffset.UTC), TestKeys.pem(TestKeys.generate()));
        String forged = other.signTicket("tkt_abc", "+263771234512", "dev", SignInPurpose.SIGN_IN, T0.plusSeconds(120));
        LoginTicketSigner ours = signer(Clock.fixed(T0, ZoneOffset.UTC), TestKeys.privatePem());
        assertThatThrownBy(() -> ours.verifyTicket(forged)).isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(LoginTicketSigner.TicketExpiredException.class);
    }

    @Test
    @DisplayName("a step-up proof is not a login ticket")
    void stepUpProof_isNotATicket() {
        LoginTicketSigner s = signer(Clock.fixed(T0, ZoneOffset.UTC), TestKeys.privatePem());
        String proof = s.signStepUpProof("+263771234512", "dev", "LARGE_TRANSFER", T0.plusSeconds(300));
        assertThatThrownBy(() -> s.verifyTicket(proof)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void escapedNewlinesInTheEnvValue_areAccepted() {
        String escaped = TestKeys.privatePem().replace("\n", "\\n");
        assertThat(signer(Clock.systemUTC(), escaped).isConfigured()).isTrue();
    }

    @Test
    void unconfigured_hasNoKeys() {
        LoginTicketSigner s = signer(Clock.systemUTC(), "");
        assertThat(s.isConfigured()).isFalse();
        assertThat((List<?>) s.jwks().get("keys")).isEmpty();
    }

    @Test
    void malformedKey_failsAtConstruction() {
        assertThatThrownBy(() -> signer(Clock.systemUTC(), "-----BEGIN PRIVATE KEY-----\nnope\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalStateException.class);
    }
}
