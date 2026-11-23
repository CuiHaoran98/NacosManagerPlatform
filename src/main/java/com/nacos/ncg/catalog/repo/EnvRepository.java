package com.nacos.ncg.catalog.repo;

import com.nacos.ncg.catalog.domain.Env;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface EnvRepository extends CrudRepository<Env, String> {

    Optional<Env> findByEnvCode(String envCode);

    List<Env> findAllByOrderBySortOrderAsc();
}
