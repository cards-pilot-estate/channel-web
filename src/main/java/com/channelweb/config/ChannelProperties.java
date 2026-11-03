package com.channelweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param gatewayUrl      the API gateway's in-cluster address (channels never call the platform directly)
 * @param apiHost         the Host header for this environment's gateway listener
 * @param clientId        this BFF's client id (the token's azp): the audience of OTP proofs
 * @param channelId       the X-Channel-ID this client may assert
 * @param customerKeySecret keys customerKey = HMAC(normalised mobile); never logged
 * @param draftKey        base64 AES-256 key for draft answers at rest
 * @param secureCookies   true behind TLS (cookies become __Host- and Secure)
 * @param noticeVersion   the privacy notice the consent screen shows
 * @param mitcVersion     the KFS and MITC version the card screen shows
 * @param draftRetention  a draft is purged this long after its last change (DPDP storage limitation)
 * @param stepUpValidity  how long a step-up code allows withdrawal or erasure
 * @param pollSchedule    seconds between status polls (R4), then the last value repeats
 * @param pollMaxSeconds  polling stops after this; the customer can check again
 */
@ConfigurationProperties(prefix = "app.channel")
public record ChannelProperties(
        String gatewayUrl,
        String apiHost,
        String clientId,
        String channelId,
        String customerKeySecret,
        String draftKey,
        boolean secureCookies,
        String noticeVersion,
        String mitcVersion,
        String brand,
        Duration draftRetention,
        Duration stepUpValidity,
        List<Integer> pollSchedule,
        int pollMaxSeconds) {
}
