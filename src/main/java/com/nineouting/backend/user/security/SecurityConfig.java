package com.nineouting.backend.user.security;

import com.nineouting.backend.common.apidocs.ApiDocsPaths;
import com.nineouting.backend.common.security.handler.JsonAccessDeniedHandler;
import com.nineouting.backend.common.security.handler.JsonAuthenticationEntryPoint;
import com.nineouting.backend.common.security.jwt.JwksKeyLocator;
import com.nineouting.backend.common.security.jwt.JwtAuthenticationFilter;
import com.nineouting.backend.common.security.jwt.JwtTokenVerifier;
import com.nineouting.backend.common.security.web.CurrentUserArgumentResolver;
import com.nineouting.backend.user.config.UserServiceProperties;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * user-service는 토큰을 직접 발급하므로 security-common의 자동 설정(jwks-uri로 공개키를 받아 옴)을 쓰지 않고,
 * 같은 검증 부품에 자기 공개키를 직접 넣는다.
 */
@Configuration
@EnableConfigurationProperties(UserServiceProperties.class)
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/auth/**",
            "/.well-known/jwks.json",
            "/actuator/health", "/actuator/health/**", "/actuator/prometheus",
            // 서비스 간 조회용. Gateway가 외부로 열지 않는다 (docs/services/user-service.md 4.3)
            "/internal/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                            JsonAuthenticationEntryPoint entryPoint,
                                            JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(ApiDocsPaths.PUBLIC).permitAll()   // API 문서 (Swagger UI)
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtKeys jwtKeys(UserServiceProperties properties) {
        return JwtKeys.from(properties.jwt());
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(JwtKeys jwtKeys, UserServiceProperties properties, Clock clock) {
        return new AccessTokenIssuer(jwtKeys, properties.jwt().accessTokenTtl(), clock);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(AccessTokenIssuer accessTokenIssuer) {
        JwtTokenVerifier verifier = new JwtTokenVerifier(
                new JwksKeyLocator(accessTokenIssuer::jwksJson), AccessTokenIssuer.ISSUER);
        return new JwtAuthenticationFilter(verifier);
    }

    /** Filter 빈이 서블릿 필터로도 등록되어 두 번 실행되지 않게 한다. */
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(JsonMapper jsonMapper) {
        return new JsonAuthenticationEntryPoint(jsonMapper);
    }

    @Bean
    JsonAccessDeniedHandler jsonAccessDeniedHandler(JsonMapper jsonMapper) {
        return new JsonAccessDeniedHandler(jsonMapper);
    }

    @Bean
    WebMvcConfigurer currentUserArgumentResolverConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CurrentUserArgumentResolver());
            }
        };
    }
}
