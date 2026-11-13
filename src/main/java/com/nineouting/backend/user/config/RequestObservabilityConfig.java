package com.nineouting.backend.user.config;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Configuration(proxyBeanMethods = false)
class RequestObservabilityConfig {

    private static final Logger REQUEST_LOG = LoggerFactory.getLogger("http.access");
    private static final String SMOKE_AGENT = "nineouting-k6-smoke";

    @Bean
    DefaultServerRequestObservationConvention requestObservationConvention() {
        return new DefaultServerRequestObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
                return super.getLowCardinalityKeyValues(context)
                        .and(KeyValue.of("traffic", traffic(context.getCarrier())));
            }
        };
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> requestAccessLog() {
        var registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getServletPath();
                return !"k6_smoke".equals(traffic(request))
                        && (path.equals("/actuator") || path.startsWith("/actuator/"));
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                long started = System.nanoTime();
                boolean completed = false;
                try {
                    chain.doFilter(request, response);
                    completed = true;
                } finally {
                    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                    REQUEST_LOG.atInfo()
                            .addKeyValue("method", request.getMethod())
                            .addKeyValue("route", pattern == null ? "unmatched" : pattern.toString())
                            .addKeyValue("status", completed ? response.getStatus() : 500)
                            .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000.0)
                            .addKeyValue("traffic", traffic(request))
                            .addKeyValue("requestTraceId", MDC.get("traceId"))
                            .log("HTTP request completed");
                }
            }
        });
        // Run inside the server observation so its trace ID is still in MDC.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    private static String traffic(HttpServletRequest request) {
        if (request != null && "GET".equals(request.getMethod())
                && "/actuator/health".equals(request.getRequestURI())
                && SMOKE_AGENT.equals(request.getHeader("User-Agent"))) {
            return "k6_smoke";
        }
        return "regular";
    }
}
