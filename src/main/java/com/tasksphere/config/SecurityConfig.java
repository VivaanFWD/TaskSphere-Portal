package com.tasksphere.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SessionAuthenticationFilter sessionAuthenticationFilter) throws Exception {
        http
            .csrf(org.springframework.security.config.Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login", "/account-recovery", "/recovery/**",
                    "/css/**", "/js/**", "/webjars/**", "/error", "/access-denied").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/password/change").hasAnyRole("STUDENT", "FACULTY", "ACADEMIC_OFFICE")
                .requestMatchers("/dashboard", "/academic-dashboard")
                    .hasAnyRole("STUDENT", "FACULTY", "ACADEMIC_OFFICE")
                .requestMatchers("/office/**").hasRole("ACADEMIC_OFFICE")
                .anyRequest().authenticated())
            .formLogin(form -> form.disable())
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, exception) ->
                response.sendRedirect("/login"))
                .accessDeniedPage("/access-denied"))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessUrl("/"))
            .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public UserDetailsService disabledLocalPasswordAuthentication() {
        return username -> {
            throw new UsernameNotFoundException("Use the configured identity service.");
        };
    }
}
