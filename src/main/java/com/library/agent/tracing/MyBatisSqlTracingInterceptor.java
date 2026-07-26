package com.library.agent.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Connection;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis SQL 执行追踪拦截器。
 * <p>
 * 拦截 StatementHandler.query/update 操作，为每条 SQL 创建子 Span，
 * 记录 SQL 文本、参数和受影响行数。
 * <p>
 * 该拦截器由 MyBatis Spring Boot 自动发现并安装，无需额外 @Bean 注册。
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query",
                args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class})
})
public class MyBatisSqlTracingInterceptor implements Interceptor {

    private static final Pattern SQL_OP_PATTERN = Pattern.compile(
            "^\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE)",
            Pattern.CASE_INSENSITIVE
    );
    private static final int MAX_SQL_LENGTH = 2000;
    private static final int MAX_PARAMS_LENGTH = 500;

    private final Tracer tracer;

    public MyBatisSqlTracingInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        BoundSql boundSql = statementHandler.getBoundSql();
        String sql = boundSql.getSql();
        String operation = extractOperation(sql);

        Span span = tracer.nextSpan()
                .name("db." + operation + " mybatis")
                .start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag(TracingConstant.TAG_DB_SYSTEM, "postgresql");
            span.tag(TracingConstant.TAG_DB_OPERATION, operation);
            span.tag(TracingConstant.TAG_DB_STATEMENT, truncate(sql, MAX_SQL_LENGTH));

            Object paramObj = boundSql.getParameterObject();
            if (paramObj != null) {
                span.tag(TracingConstant.TAG_DB_PARAMS,
                        truncate(paramObj.toString(), MAX_PARAMS_LENGTH));
            }

            long start = System.currentTimeMillis();
            Object result = invocation.proceed();
            long duration = System.currentTimeMillis() - start;
            span.tag(TracingConstant.TAG_DURATION_MS, String.valueOf(duration));

            return result;

        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public Object plugin(Object target) {
        return Interceptor.super.plugin(target);
    }

    @Override
    public void setProperties(java.util.Properties properties) {
    }

    /**
     * 从 SQL 文本首部提取操作类型。
     */
    private String extractOperation(String sql) {
        if (sql == null || sql.isBlank()) {
            return "UNKNOWN";
        }
        Matcher m = SQL_OP_PATTERN.matcher(sql.trim());
        if (m.find()) {
            return m.group(1).toUpperCase();
        }
        return "UNKNOWN";
    }

    /**
     * 截断过长文本，防止 Span Tag 过大。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...[truncated]";
    }
}
