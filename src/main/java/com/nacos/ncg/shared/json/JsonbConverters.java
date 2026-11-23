package com.nacos.ncg.shared.json;

import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

import java.sql.SQLException;

/**
 * Jsonb ↔ PGobject（jsonb）转换对。注册见 {@link JdbcConfig}。
 */
public final class JsonbConverters {

    private JsonbConverters() {
    }

    @WritingConverter
    public enum JsonbToPgObject implements Converter<Jsonb, PGobject> {
        INSTANCE;

        @Override
        public PGobject convert(Jsonb source) {
            PGobject pg = new PGobject();
            try {
                pg.setType("jsonb");
                pg.setValue(source.json());
            } catch (SQLException e) {
                throw new IllegalArgumentException("cannot bind jsonb value", e);
            }
            return pg;
        }
    }

    @ReadingConverter
    public enum PgObjectToJsonb implements Converter<PGobject, Jsonb> {
        INSTANCE;

        @Override
        public Jsonb convert(PGobject source) {
            return new Jsonb(source.getValue());
        }
    }
}
