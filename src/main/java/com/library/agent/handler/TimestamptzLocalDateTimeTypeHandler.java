package com.library.agent.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * PG TIMESTAMPTZ 列与 Java LocalDateTime 的互转处理器。
 * <p>
 * MyBatis 默认的 LocalDateTime 处理器读取时走 rs.getObject(column, LocalDateTime.class)，
 * pgjdbc 对 TIMESTAMPTZ 列不支持该转换（需 OffsetDateTime），会抛
 * "Cannot convert the column of type TIMESTAMPTZ to requested type LocalDateTime"。
 * 本处理器改走 rs.getTimestamp(...).toLocalDateTime()：先把 TIMESTAMPTZ 按驱动转换
 * 为 JVM 时区的 Timestamp，再取本地时间；写侧 setTimestamp(Timestamp.valueOf(...)) 与之对称。
 * 仅用于时间列为 TIMESTAMPTZ 的表（如 agent_long_term_memory）。
 */
public class TimestamptzLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setTimestamp(i, Timestamp.valueOf(parameter));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toLocal(rs.getTimestamp(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toLocal(rs.getTimestamp(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toLocal(cs.getTimestamp(columnIndex));
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
