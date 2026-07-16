package xyz.yychainsaw.portfolio.config;

import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import xyz.yychainsaw.portfolio.auth.session.AdminSessionService;
import xyz.yychainsaw.portfolio.auth.web.LoginSubjectHasher;
import xyz.yychainsaw.portfolio.auth.web.SecurityProblemWriter;
import xyz.yychainsaw.portfolio.auth.web.SessionMetadataEnforcementFilter;
import xyz.yychainsaw.portfolio.auth.web.SessionPersistenceConcurrencyFilter;
import xyz.yychainsaw.portfolio.common.ratelimit.RateLimitProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    private static final String API_CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'";
    private static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=()";
    private static final List<String> CORS_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final List<String> CORS_REQUEST_HEADERS =
            List.of("Content-Type", "X-XSRF-TOKEN");
    private static final List<String> CORS_EXPOSED_HEADERS =
            List.of("X-Trace-Id", "Retry-After");

    @Bean(name = "cookieSerializer")
    DefaultCookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("PORTFOLIO_SESSION");
        serializer.setCookiePath("/");
        serializer.setCookieMaxAge(-1);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite("Strict");
        serializer.setUseBase64Encoding(false);
        return serializer;
    }

    @Bean(name = "httpSessionIdResolver")
    CookieHttpSessionIdResolver httpSessionIdResolver(DefaultCookieSerializer cookieSerializer) {
        CookieHttpSessionIdResolver resolver = new CookieHttpSessionIdResolver();
        resolver.setCookieSerializer(cookieSerializer);
        return resolver;
    }

    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        HttpSessionSecurityContextRepository repository =
                new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return repository;
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    SecurityContextLogoutHandler securityContextLogoutHandler(
            SecurityContextRepository securityContextRepository) {
        SecurityContextLogoutHandler handler = new SecurityContextLogoutHandler();
        handler.setInvalidateHttpSession(true);
        handler.setClearAuthentication(true);
        handler.setSecurityContextRepository(securityContextRepository);
        return handler;
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setParameterName("_csrf");
        repository.setCookieHttpOnly(false);
        repository.setCookiePath("/");
        repository.setCookieMaxAge(-1);
        repository.setSecure(secure);
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .httpOnly(false)
                .secure(secure)
                .sameSite("Strict")
                .maxAge(-1));
        return repository;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${portfolio.web.allow-development-cors:false}") boolean enabled,
            @Value("${portfolio.web.development-origin:http://localhost:5174}")
                    String configuredOrigin) {
        CorsConfiguration cors = new CorsConfiguration();
        if (enabled) {
            cors.setAllowedOrigins(List.of(requireAbsoluteOrigin(configuredOrigin)));
            cors.setAllowedMethods(CORS_METHODS);
            cors.setAllowedHeaders(CORS_REQUEST_HEADERS);
            cors.setExposedHeaders(CORS_EXPOSED_HEADERS);
            cors.setAllowCredentials(true);
            cors.setMaxAge(3600L);
        } else {
            cors.setAllowedOrigins(List.of());
            cors.setAllowedMethods(List.of());
            cors.setAllowedHeaders(List.of());
            cors.setExposedHeaders(List.of());
            cors.setAllowCredentials(false);
            cors.setMaxAge(0L);
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AdminSessionService sessions,
            SecurityContextRepository securityContexts,
            CookieCsrfTokenRepository csrfTokens,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
            SecurityProblemWriter problems) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");

        SessionMetadataEnforcementFilter metadataEnforcement =
                new SessionMetadataEnforcementFilter(sessions, securityContexts, problems);

        http
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .enableSessionUrlRewriting(false))
                .securityContext(context -> context
                        .requireExplicitSave(true)
                        .securityContextRepository(securityContexts))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .cors(corsConfigurer -> corsConfigurer.configurationSource(cors))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                problems.write(response, HttpStatus.UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request, response, failure) -> {
                            if (failure instanceof CsrfException) {
                                problems.write(response, HttpStatus.FORBIDDEN, "CSRF_INVALID");
                            } else {
                                problems.write(response, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
                            }
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET,
                                "/api/admin/auth/csrf", "/actuator/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/admin/auth/password",
                                "/api/admin/auth/second-factor")
                        .anonymous()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().permitAll())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31_536_000)
                                .includeSubDomains(true))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                AntPathRequestMatcher.antMatcher("/api/**"),
                                new ContentSecurityPolicyHeaderWriter(
                                        API_CONTENT_SECURITY_POLICY)))
                        .permissionsPolicy(policy -> policy.policy(PERMISSIONS_POLICY)))
                .addFilterAfter(metadataEnforcement, CsrfFilter.class);

        return http.build();
    }

    @Bean
    FilterRegistrationBean<SessionPersistenceConcurrencyFilter>
            sessionPersistenceConcurrencyFilterRegistration(
                    HttpSessionIdResolver sessionIds,
                    LoginSubjectHasher subjects,
                    RateLimitProperties rateLimits,
                    SecurityProblemWriter problems) {
        SessionPersistenceConcurrencyFilter filter =
                new SessionPersistenceConcurrencyFilter(
                        sessionIds, subjects, rateLimits, problems);
        FilterRegistrationBean<SessionPersistenceConcurrencyFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setName("sessionPersistenceConcurrencyFilter");
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.setAsyncSupported(false);
        registration.setMatchAfter(false);
        registration.addUrlPatterns("/*");
        return registration;
    }

    private static String requireAbsoluteOrigin(String configured) {
        if (configured == null
                || configured.isBlank()
                || configured.indexOf('\r') >= 0
                || configured.indexOf('\n') >= 0
                || configured.equals("*")
                || configured.equalsIgnoreCase("null")) {
            throw invalidOrigin();
        }

        URI origin;
        try {
            origin = new URI(configured);
        } catch (URISyntaxException failure) {
            throw invalidOrigin();
        }
        String scheme = origin.getScheme();
        String path = origin.getRawPath();
        if (!("http".equals(scheme) || "https".equals(scheme))
                || origin.getHost() == null
                || origin.getRawAuthority() == null
                || origin.getRawUserInfo() != null
                || path != null && !path.isEmpty()
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || origin.getPort() > 65_535) {
            throw invalidOrigin();
        }
        return configured;
    }

    private static IllegalArgumentException invalidOrigin() {
        return new IllegalArgumentException("development origin is invalid");
    }
}
