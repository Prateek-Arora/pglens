package com.pglens.server.web;

import com.pglens.server.auth.AuthService;
import com.pglens.server.auth.Caller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The HTTP API's single authentication choke point (ADR-0044), the counterpart of the gRPC {@code
 * AuthInterceptor}: resolves the bearer token to a {@link Caller} and grants {@code ROLE_<role>},
 * plus {@code WRITE} for anything but an API token — API tokens are read-only by construction. An
 * unknown or expired token just leaves the request anonymous; the security rules then answer 401.
 */
final class BearerTokenFilter extends OncePerRequestFilter {

  static final String WRITE = "WRITE";

  private final AuthService auth;

  BearerTokenFilter(AuthService auth) {
    this.auth = auth;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    auth.authenticate(BearerTokens.from(request))
        .ifPresent(
            caller -> {
              List<GrantedAuthority> authorities = new ArrayList<>();
              authorities.add(new SimpleGrantedAuthority("ROLE_" + caller.role().name()));
              if (caller.via() != Caller.Via.API_TOKEN) {
                authorities.add(new SimpleGrantedAuthority(WRITE));
              }
              SecurityContextHolder.getContext()
                  .setAuthentication(
                      UsernamePasswordAuthenticationToken.authenticated(caller, null, authorities));
            });
    if (auth.properties().disabled()) {
      response.setHeader("X-PgLens-Auth", "none");
    }
    chain.doFilter(request, response);
  }
}
