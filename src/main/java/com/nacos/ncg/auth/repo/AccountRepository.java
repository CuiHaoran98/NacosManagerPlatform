package com.nacos.ncg.auth.repo;

import com.nacos.ncg.auth.domain.Account;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface AccountRepository extends CrudRepository<Account, String> {

    Optional<Account> findByLoginName(String loginName);
}
