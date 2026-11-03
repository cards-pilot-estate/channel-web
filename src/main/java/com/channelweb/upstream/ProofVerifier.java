package com.channelweb.upstream;

import com.channelweb.config.ChannelProperties;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/**
 * Checks otp-service's proof instead of trusting "verified" (ASVS 6.6.2): signature against the
 * service's keys (fetched through the gateway, refreshed on an unknown key id), audience = this
 * client, subject = this customer, purpose, challenge and binding as expected, not expired.
 */
@Component
public class ProofVerifier {

    private static final Duration SKEW = Duration.ofSeconds(30);

    private final GatewayClient gateway;
    private final ChannelProperties properties;
    private final Clock clock;
    private volatile JWKSet keys = new JWKSet();

    public ProofVerifier(GatewayClient gateway, ChannelProperties properties, Clock clock) {
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return the proof's jti, which the caller keeps so a proof is never accepted twice */
    public String verify(String proof, String customerKey, String purpose, String challengeId, String bindingHash) {
        try {
            SignedJWT jwt = SignedJWT.parse(proof);
            JWK key = keyFor(jwt.getHeader().getKeyID());
            if (key == null || !jwt.verify(new ECDSAVerifier(key.toECKey()))) {
                throw new InvalidProofException("signature");
            }
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            Instant now = clock.instant();
            require(c.getAudience().contains(properties.clientId()), "audience");
            require(customerKey.equals(c.getSubject()), "subject");
            require(purpose.equals(c.getStringClaim("pur")), "purpose");
            require(challengeId.equals(c.getStringClaim("cid")), "challenge");
            require(bindingHash.equals(c.getStringClaim("bnd")), "binding");
            Date exp = c.getExpirationTime();
            require(exp != null && now.minus(SKEW).isBefore(exp.toInstant()), "expired");
            return Objects.requireNonNull(c.getJWTID(), "jti");
        } catch (InvalidProofException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidProofException("unreadable");
        }
    }

    private JWK keyFor(String keyId) throws Exception {
        JWK key = keys.getKeyByKeyId(keyId);
        if (key == null) {
            keys = JWKSet.parse(gateway.otpKeys());
            key = keys.getKeyByKeyId(keyId);
        }
        return key;
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new InvalidProofException(what);
        }
    }

    public static class InvalidProofException extends RuntimeException {
        public InvalidProofException(String reason) {
            super("OTP proof rejected: " + reason);
        }
    }
}
