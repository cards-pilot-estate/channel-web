package com.channelweb.api;

import com.channelweb.config.ChannelProperties;
import com.channelweb.domain.CustomerKeys;
import com.channelweb.security.CustomerSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Session state for the SPA, extend, sign out, and the static product catalogue (tier A). */
@RestController
public class SessionController {

    private final ChannelProperties properties;

    public SessionController(ChannelProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/bff/session")
    public Map<String, Object> session(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String customer = CustomerSession.customerKey();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", customer != null);
        if (customer != null) {
            body.put("maskedMobile", CustomerKeys.masked(CustomerSession.mobile(session)));
            body.put("idleTimeoutSeconds", session.getMaxInactiveInterval());
        }
        body.put("brand", properties.brand());
        body.put("noticeVersion", properties.noticeVersion());
        body.put("mitcVersion", properties.mitcVersion());
        body.put("polling", Map.of("scheduleSeconds", properties.pollSchedule(), "maxSeconds", properties.pollMaxSeconds()));
        return body;
    }

    @PostMapping("/bff/session/extend")
    public ResponseEntity<Void> extend() {
        return ResponseEntity.noContent().build();   // touching the session resets the idle timer
    }

    @PostMapping("/bff/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Map.of("redirectUrl", "/signed-out");
    }

    @PostMapping("/bff/csp-report")
    public ResponseEntity<Void> cspReport(@RequestBody(required = false) String ignored) {
        return ResponseEntity.noContent().build();
    }

    /** Illustrative products and fees under the fictional brand (W10.01 replaces this with the catalogue). */
    @GetMapping("/bff/catalogue")
    public Map<String, Object> catalogue() {
        return Map.of("cards", List.of(
                card("BASIC", "Everyday", 0, 0, "1% back on everything"),
                card("GOLD", "Gold", 499, 499, "2x points on dining and travel"),
                card("PLATINUM", "Platinum", 1499, 1499, "Lounge access, 3x points"),
                card("SIGNATURE", "Signature", 4999, 4999, "Concierge, 5x points")),
                "kfsDocument", "/documents/kfs-" + properties.mitcVersion() + ".html");
    }

    private static Map<String, Object> card(String code, String name, int joiningFee, int annualFee, String benefit) {
        return Map.of("code", code, "name", name, "joiningFee", joiningFee, "annualFee", annualFee, "benefit", benefit);
    }
}
