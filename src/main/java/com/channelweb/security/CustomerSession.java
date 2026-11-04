package com.channelweb.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/** Signs a verified customer in: a new session id (ASVS 7.2.4), principal = customerKey. */
@Component
public class CustomerSession {

    public static final String MOBILE = "customer.mobile";
    public static final String STEP_UP_AT = "customer.stepUpAt";
    /** The step-up proof's id: the verification reference recorded with an offer answer (W10.04). */
    public static final String STEP_UP_REF = "customer.stepUpRef";

    private final SecurityContextRepository contexts;

    public CustomerSession(SecurityContextRepository contexts) {
        this.contexts = contexts;
    }

    public void signIn(String customerKey, String mobile, HttpServletRequest request, HttpServletResponse response) {
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(customerKey, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession().setAttribute(MOBILE, mobile);
    }

    /** The signed-in customer's key, or {@code null}. */
    public static String customerKey() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_CUSTOMER")) ? (String) auth.getPrincipal() : null;
    }

    public static String mobile(HttpSession session) {
        return session == null ? null : (String) session.getAttribute(MOBILE);
    }
}
