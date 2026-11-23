package com.nacos.ncg.notification.repo;

import com.nacos.ncg.notification.domain.NotifyBinding;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface NotifyBindingRepository extends CrudRepository<NotifyBinding, String> {

    List<NotifyBinding> findByAppIdAndEnvId(String appId, String envId);

    Optional<NotifyBinding> findByAppIdAndEnvIdAndChannel(String appId, String envId, String channel);

    List<NotifyBinding> findAll();
}
