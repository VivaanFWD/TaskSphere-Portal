package com.tasksphere.config;

import com.tasksphere.dto.AuthTokenResponse;
import com.tasksphere.dto.UserIdentityResponse;
import com.tasksphere.service.AuthClientService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final AuthClientService authClientService;

    public SessionAuthenticationFilter(AuthClientService authClientService) {
        this.authClientService = authClientService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/")
            || path.equals("/login")
            || path.equals("/logout")
            || path.equals("/access-denied")
            || path.equals("/error")
            || path.startsWith("/css/")
            || path.startsWith("/js/")
            || path.startsWith("/webjars/")
            || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        var session = request.getSession(false);
        Object sessionUser = session == null ? null : session.getAttribute("currentUser");
        Object sessionToken = session == null ? null : session.getAttribute("token");
        if (sessionUser instanceof AuthTokenResponse user && sessionToken instanceof String token) {
            try {
                UserIdentityResponse latest = authClientService.me(token);
                if (latest.roles() == null || !"ACTIVE".equals(latest.status())) {
                    session.invalidate();
                    response.sendRedirect("/login");
                    return;
                }
                AuthTokenResponse refreshed = new AuthTokenResponse();
                refreshed.setToken(user.getToken());
                refreshed.setTokenType(user.getTokenType());
                refreshed.setUsername(latest.username());
                refreshed.setDisplayName(latest.firstName() + " " + latest.lastName());
                refreshed.setPasswordChangeRequired(latest.passwordChangeRequired());
                refreshed.setRoles(latest.roles());
                session.setAttribute("currentUser", refreshed);
                if (latest.passwordChangeRequired()
                    && !request.getServletPath().equals("/password/change")
                    && !request.getServletPath().equals("/logout")) {
                    response.sendRedirect("/password/change");
                    return;
                }
                List<SimpleGrantedAuthority> authorities = latest.roles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(latest.username(), null, authorities)
                );
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                    session.invalidate();
                    response.sendRedirect("/login");
                } else {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Identity verification failed");
                }
                return;
            } catch (ResourceAccessException ex) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Authentication service is unavailable");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    @Bean
    public FilterRegistrationBean<SessionAuthenticationFilter> sessionFilterRegistration(
        SessionAuthenticationFilter filter
    ) {
        FilterRegistrationBean<SessionAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
