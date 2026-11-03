package com.channelweb.domain;

import com.channelweb.config.ChannelProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The customer's opaque key: HMAC-SHA256 of the normalised mobile number (R13). It identifies the
 * customer to this channel, to the gateway's per-customer limit and as the OTP proof's subject,
 * without revealing the number.
 */
@Component
public class CustomerKeys {

    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9][0-9]{9}$");

    private final byte[] secret;

    public CustomerKeys(ChannelProperties properties) {
        this.secret = properties.customerKeySecret().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code +91XXXXXXXXXX}, or {@code null} if the input is not an Indian mobile number. */
    public static String normalise(String mobile) {
        if (mobile == null) {
            return null;
        }
        String digits = mobile.replaceAll("[\\s()-]", "");
        if (digits.startsWith("+91")) {
            digits = digits.substring(3);
        } else if (digits.startsWith("0") && digits.length() == 11) {
            digits = digits.substring(1);
        }
        return INDIAN_MOBILE.matcher(digits).matches() ? "+91" + digits : null;
    }

    public String keyOf(String normalisedMobile) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(normalisedMobile.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    public static String masked(String normalisedMobile) {
        return "XXXXXX" + normalisedMobile.substring(normalisedMobile.length() - 4);
    }
}
