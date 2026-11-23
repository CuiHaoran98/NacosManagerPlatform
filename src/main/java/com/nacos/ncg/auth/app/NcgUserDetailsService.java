package com.nacos.ncg.auth.app;

import com.nacos.ncg.auth.domain.Account;
import com.nacos.ncg.auth.repo.AccountRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 会话认证细节（R-AUTH-06~08）：禁用/冻结/锁定即时反映到登录判定。
 * 权限点（应用×环境）不进 authorities，由 GrantService 在服务内判定。
 */
@Service
public class NcgUserDetailsService implements UserDetailsService {

    private final AccountRepository accounts;

    public NcgUserDetailsService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String loginName) throws UsernameNotFoundException {
        Account a = accounts.findByLoginName(loginName)
                .orElseThrow(() -> new UsernameNotFoundException("unknown login: " + loginName));
        if ("DISABLED".equals(a.getStatus())) {
            throw new org.springframework.security.authentication.DisabledException("account disabled");
        }
        boolean frozen = "FROZEN".equals(a.getStatus())
                || (a.getLockedUntil() != null && a.getLockedUntil().isAfter(java.time.Instant.now()));
        if (frozen) {
            throw new org.springframework.security.authentication.LockedException("account locked");
        }
        return User.withUsername(a.getLoginName())
                .password(a.getPasswordHash())
                .authorities(java.util.List.of())
                .build();
    }
}
