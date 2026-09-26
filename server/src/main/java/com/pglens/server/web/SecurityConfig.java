package com.pglens.server.web;

import com.pglens.server.auth.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * HTTP API security (ADR-0044). Stateless bearer tokens only: no cookies, form login, HTTP basic or
 * server session. That is also why CSRF and CORS are off — a browser never sends credentials to
 * this API on its own (the dashboard's server code calls it on the private network).
 *
 * <p>Rules, in order: login and health are public; user management and database registration need
 * ADMIN; every other GET needs a login; every other write also needs {@code WRITE}, which API
 * tokens never get.
 */
@Configuration
@ConditionalOnWebApplication(
    type = ConditionalOnWebApplication.Type.SERVLET) // gRPC-only runs skip it
class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http, AuthService auth) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // Not a bean on purpose: Boot would also register a Filter bean as a servlet filter.
        .addFilterBefore(new BearerTokenFilter(auth), AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/openapi.json")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    .requestMatchers("/api/v1/users", "/api/v1/users/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/databases", "/api/v1/databases/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/databases/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/**")
                    .authenticated()
                    .anyRequest()
                    .hasAuthority(BearerTokenFilter.WRITE))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) ->
                            problem(
                                res,
                                401,
                                "Unauthorized",
                                "Log in, or send a valid token as 'Authorization: Bearer …'."))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            problem(
                                res,
                                403,
                                "Forbidden",
                                "Your role or token doesn't allow this (API tokens are"
                                    + " read-only).")))
        .build();
  }

  // Fixed strings only, so hand-written JSON is safe here.
  private static void problem(HttpServletResponse res, int status, String title, String detail)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/problem+json");
    if (status == 401) {
      res.setHeader("WWW-Authenticate", "Bearer");
    }
    res.getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\""
                + title
                + "\",\"status\":"
                + status
                + ",\"detail\":\""
                + detail
                + "\"}");
  }
}
