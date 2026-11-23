package com.nacos.ncg.shared.json;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

import java.util.List;

/**
 * Spring Data JDBC 转换注册（jsonb 列 ↔ {@link Jsonb}）。
 */
@Configuration
public class JdbcConfig {

    @Bean
    public JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(
                JsonbConverters.JsonbToPgObject.INSTANCE,
                JsonbConverters.PgObjectToJsonb.INSTANCE));
    }
}
