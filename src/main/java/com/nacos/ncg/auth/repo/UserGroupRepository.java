package com.nacos.ncg.auth.repo;

import com.nacos.ncg.auth.domain.UserGroup;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface UserGroupRepository extends CrudRepository<UserGroup, String> {

    Optional<UserGroup> findByGroupCode(String groupCode);
}
