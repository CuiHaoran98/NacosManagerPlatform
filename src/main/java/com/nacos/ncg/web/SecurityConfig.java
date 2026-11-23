package com.nacos.ncg.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nacos.ncg.audit.app.AuditWriter;
import com.nacos.ncg.audit.domain.ActorType;
import com.nacos.ncg.audit.domain.AuditAction;
import com.nacos.ncg.auth.app.AccountService;
import com.nacos.ncg.auth.domain.Account;
import com.nacos.ncg.auth.repo.AccountRepository;
import com.nacos.ncg.shared.NcgErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * 会话安全（FR-06 / R-AUTH-06/09/10 / §6.1）：
 * Cookie NCG_SESSION（HttpOnly/SameSite=Lax/Secure 可配）+ CSRF（XSRF-TOKEN / X-XSRF-TOKEN）；
 * 无会话即 401（fail-closed）；登录接口豁免 CSRF（凭证本身即授权）；
 * 密码 Argon2id（A-08）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AccountRepository accounts,
                                                   AccountService accountService,
                                                   AuditWriter auditWriter,
                                                   com.nacos.ncg.auth.repo.GrantRepository grantRepository,
                                                   PlatformTransactionManager txm) throws Exception {
        ObjectMapper json = JSON_MAPPER;
        TransactionTemplate tx = new TransactionTemplate(txm);

        http.csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // 明文令牌（API 客户端直接回显 cookie 值；XOR 掩码令牌对 curl 不友好）
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/v1/auth/login"))
                // 强制触发延迟令牌写 cookie：否则 XSRF-TOKEN cookie 永远不下发
                .addFilterAfter((req, res, chain) -> {
                    CsrfToken t = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
                    if (t == null) {
                        t = (CsrfToken) req.getAttribute("_csrf");
                    }
                    if (t != null) {
                        t.getToken();
                    }
                    chain.doFilter(req, res);
                }, org.springframework.security.web.csrf.CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/v1/auth/login")
                        .successHandler((req, res, auth) -> {
                            String login = auth.getName();
                            accountService.loginSucceeded(login);
                            Account a = accounts.findByLoginName(login).orElse(null);
                            tx.executeWithoutResult(s -> auditWriter.append(new AuditWriter.Command(
                                    ActorType.USER, login, req.getRemoteAddr(), AuditAction.LOGIN_SUCCESS,
                                    "ACCOUNT", a == null ? login : a.getAccountId(),
                                    null, null, true, null, null, null)));
                            // 契约：登录响应同 CurrentUser 形状（userId/username/roles/permissions + 旧字段）
                            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                            if (a != null) {
                                body.putAll(ContractViews.userRow(a));
                                body.put("permissions", ContractViews.permissionsOf(a, grantRepository));
                            } else {
                                body.put("userId", "");
                                body.put("username", login);
                                body.put("displayName", login);
                                body.put("roles", java.util.List.of("USER"));
                                body.put("permissions", java.util.List.of());
                                body.put("accountId", "");
                                body.put("loginName", login);
                                body.put("isAdmin", false);
                            }
                            writeJson(res, HttpServletResponse.SC_OK, body);
                        })
                        .failureHandler((req, res, ex) -> {
                            String login = req.getParameter("username");
                            if (login != null) {
                                accountService.loginFailed(login);
                            }
                            tx.executeWithoutResult(s -> auditWriter.append(new AuditWriter.Command(
                                    ActorType.USER, login == null ? "unknown" : login, req.getRemoteAddr(),
                                    AuditAction.LOGIN_FAILURE, "ACCOUNT", login == null ? "unknown" : login,
                                    null, null, false, NcgErrorCode.AUTH_401_001.code(), null, null)));
                            String code = ex instanceof BadCredentialsException
                                    ? NcgErrorCode.AUTH_401_001.code()
                                    : NcgErrorCode.AUTH_401_002.code();
                            writeJson(res, HttpServletResponse.SC_UNAUTHORIZED,
                                    Map.of("code", code, "message", "authentication failed",
                                            "timestamp", Instant.now().toString()));
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> writeJson(res, HttpServletResponse.SC_OK,
                                Map.of("message", "logged out", "timestamp", Instant.now().toString()))))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) ->
                        writeJson(res, HttpServletResponse.SC_UNAUTHORIZED,
                                Map.of("code", NcgErrorCode.AUTH_401_001.code(),
                                        "message", "authentication required (R-AUTH-10)",
                                        "timestamp", Instant.now().toString()))))
                .cors(Customizer.withDefaults());
        return http.build();
    }

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static void writeJson(HttpServletResponse res, int status, Map<String, Object> body) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        JSON_MAPPER.writeValue(res.getWriter(), body);
    }
}
