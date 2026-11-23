package com.nacos.ncg.catalog.repo;

import com.nacos.ncg.catalog.domain.App;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface AppRepository extends CrudRepository<App, String> {

    Optional<App> findByAppCode(String appCode);

    List<App> findAllByOrderByAppCodeAsc();
}
