package com.channelweb.security;

import com.channelweb.config.ChannelProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Customer BFF security (ADR-0020, ADR-0016's pattern without OIDC). The customer signs in with a
 * mobile number and a one-time code; the session lives in Redis and the browser holds only an
 * HttpOnly, SameSite=Strict cookie (__Host- and Secure behind TLS). Writes need the CSRF token
 * (cookie to header). Public: the pages, the catalogue, the session state and the sign-in calls.
 */
@Configuration
public class WebSecurityConfig {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain web(HttpSecurity http, ChannelProperties properties, SecurityContextRepository contexts) throws Exception {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(c -> c.sameSite("Strict").secure(properties.secureCookies()).path("/"));
        return http
                .securityContext(s -> s.securityContextRepository(contexts))
                .authorizeHttpRequests(r -> r
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/bff/session", "/bff/catalogue").permitAll()
                        .requestMatchers(HttpMethod.POST, "/bff/auth/otp/send", "/bff/auth/otp/resend",
                                "/bff/auth/otp/verify", "/bff/logout", "/bff/csp-report").permitAll()
                        .requestMatchers("/bff/**").hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()   // the SPA and its assets: no data
                        .anyRequest().denyAll())
                .csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers("/bff/csp-report"))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; img-src 'self' data:; "
                                + "style-src 'self'; script-src 'self'; connect-src 'self'; font-src 'self'; "
                                + "frame-ancestors 'none'; form-action 'self'; base-uri 'none'; object-src 'none'; "
                                + "report-uri /bff/csp-report"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()")))
                .requestCache(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .build();
    }

    /** The session cookie: HttpOnly, SameSite=Strict; __Host- and Secure behind TLS (R13, R15). */
    @Bean
    CookieSerializer cookieSerializer(ChannelProperties properties) {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();
        cookie.setCookieName(properties.secureCookies() ? "__Host-WEB_SESSION" : "WEB_SESSION");
        cookie.setUseSecureCookie(properties.secureCookies());
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Strict");
        cookie.setCookiePath("/");
        return cookie;
    }
}
