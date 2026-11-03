package com.channelweb.api;

import com.channelweb.domain.ChannelException;
import com.channelweb.domain.Drafts;
import com.channelweb.security.CustomerSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The customer's draft: consents first, then one section per page, then submit. */
@RestController
public class DraftController {

    public record ConsentRequest(String noticeVersion, String locale, Map<String, Boolean> purposes) { }

    private final Drafts drafts;

    public DraftController(Drafts drafts) {
        this.drafts = drafts;
    }

    @PostMapping("/bff/drafts")
    public ResponseEntity<Map<String, Object>> start(@RequestBody ConsentRequest body, HttpServletRequest request) {
        Map<String, Boolean> p = body.purposes() == null ? Map.of() : body.purposes();
        Drafts.Draft d = drafts.start(CustomerSession.customerKey(), CustomerSession.mobile(request.getSession(false)),
                new Drafts.Consents(Boolean.TRUE.equals(p.get("PROCESSING")), Boolean.TRUE.equals(p.get("BUREAU_CHECK")),
                        Boolean.TRUE.equals(p.get("MARKETING"))),
                "hi-IN".equals(body.locale()) ? "hi-IN" : "en-IN");
        return ResponseEntity.status(201).header(HttpHeaders.ETAG, String.valueOf(d.rowVersion())).body(view(d));
    }

    @GetMapping("/bff/drafts/current")
    public ResponseEntity<Map<String, Object>> current() {
        Drafts.Draft d = drafts.find(CustomerSession.customerKey()).orElseThrow(() -> new ChannelException(404, "NO_DRAFT"));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, String.valueOf(d.rowVersion())).body(view(d));
    }

    @PutMapping("/bff/drafts/current/sections/{section}")
    public ResponseEntity<Map<String, Object>> save(@PathVariable String section, @RequestHeader("If-Match") String ifMatch,
                                                    @RequestBody Map<String, Object> fields) {
        int version;
        try {
            version = Integer.parseInt(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new ChannelException(428, "IF_MATCH_REQUIRED");
        }
        Drafts.Draft d = drafts.save(CustomerSession.customerKey(), section, fields, version);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, String.valueOf(d.rowVersion())).body(view(d));
    }

    @DeleteMapping("/bff/drafts/current")
    public ResponseEntity<Void> discard() {
        drafts.discard(CustomerSession.customerKey());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/bff/drafts/current/submission")
    public ResponseEntity<Map<String, Object>> submit() {
        Drafts.Submitted s = drafts.submit(CustomerSession.customerKey());
        return ResponseEntity.accepted().body(Map.of("applicationId", s.applicationId(), "applicationRef", s.applicationRef()));
    }

    /** The answers for pre-filling pages: never the mobile number (it was verified at sign-in). */
    private static Map<String, Object> view(Drafts.Draft d) {
        Map<String, Object> answers = new LinkedHashMap<>(d.answers());
        answers.remove("mobile");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answers", answers);
        body.put("sectionsDone", d.sectionsDone());
        body.put("complete", d.complete());
        body.put("rowVersion", d.rowVersion());
        body.put("marketing", d.consents().marketing());
        body.put("updatedAt", d.updatedAt());
        body.put("expiresAt", d.expiresAt());
        return body;
    }
}
