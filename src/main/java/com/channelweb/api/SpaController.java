package com.channelweb.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Client-side routes: every page is the SPA's index.html. */
@Controller
public class SpaController {

    @GetMapping({"/", "/start", "/sign-in", "/sign-in/code", "/privacy-notice", "/apply", "/apply/**",
            "/check-answers", "/submitted/**", "/applications", "/applications/**", "/signed-out", "/accessibility"})
    public String app() {
        return "forward:/index.html";
    }
}
