package com.library.agent.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class FloatArrayTypeHandler extends BaseTypeHandler<float[]> {

    private static final int VECTOR_DIMENSION = 1536;

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, float[] parameter, JdbcType jdbcType) throws SQLException {
        if (parameter.length != VECTOR_DIMENSION) {
            throw new SQLException("Embedding vector dimension mismatch, expected "
                    + VECTOR_DIMENSION + " but got " + parameter.length);
        }

        PGobject vectorObject = new PGobject();
        vectorObject.setType("vector");
        vectorObject.setValue(toVectorLiteral(parameter));
        ps.setObject(i, vectorObject);
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseVectorLiteral(rs.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseVectorLiteral(rs.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseVectorLiteral(cs.getString(columnIndex));
    }

    private String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 12);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private float[] parseVectorLiteral(String value) throws SQLException {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new SQLException("Invalid pgvector literal: " + value);
        }

        String body = trimmed.substring(1, trimmed.length() - 1);
        if (body.isBlank()) {
            return new float[0];
        }

        String[] parts = body.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
