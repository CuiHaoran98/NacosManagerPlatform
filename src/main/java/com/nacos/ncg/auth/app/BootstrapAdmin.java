package com.nacos.ncg.auth.app;

import com.nacos.ncg.auth.domain.Account;
import com.nacos.ncg.auth.repo.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首账号引导（R-AUTH-01）：库中无任何账号且配置了 ncg.bootstrap.admin-password 时创建首个管理员；
 * 未配置密码则跳过（引导属运维动作，写 ACCOUNT_CREATE 审计）。
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AccountRepository accounts;
    private final AccountService accountService;
    private final String adminLogin;
    private final String adminPassword;

    public BootstrapAdmin(AccountRepository accounts, AccountService accountService,
                          @Value("${ncg.bootstrap.admin-login:ncg-admin}") String adminLogin,
                          @Value("${ncg.bootstrap.admin-password:}") String adminPassword) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.adminLogin = adminLogin;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.count() > 0) {
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("no accounts exist and ncg.bootstrap.admin-password is not set; "
                    + "bootstrap admin skipped (set NCG bootstrap properties to create the first admin)");
            return;
        }
        Account admin = accountService.create(adminLogin, "Bootstrap Admin", adminPassword, true,
                "bootstrap", null);
        log.info("bootstrap admin created: {}", admin.getLoginName());
    }
}
