package com.library.agent.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * Redis 操作追踪切面。
 * <p>
 * 拦截 Spring Data Redis 的 RedisTemplate 操作方法，
 * 为每次 Redis 交互创建子 Span。
 */
@Aspect
@Component
public class RedisTracingAspect {

    private final Tracer tracer;

    public RedisTracingAspect(Tracer tracer) {
        this.tracer = tracer;
    }

    @Pointcut("execution(* org.springframework.data.redis.core.RedisTemplate.*(..))"
            + " || execution(* org.springframework.data.redis.core.ValueOperations.*(..))"
            + " || execution(* org.springframework.data.redis.core.HashOperations.*(..))"
            + " || execution(* org.springframework.data.redis.core.ListOperations.*(..))"
            + " || execution(* org.springframework.data.redis.core.SetOperations.*(..))"
            + " || execution(* org.springframework.data.redis.core.ZSetOperations.*(..))")
    public void redisOperation() {
    }

    @Around("redisOperation()")
    public Object traceRedis(ProceedingJoinPoint pjp) throws Throwable {
        String methodName = pjp.getSignature().getName();
        Span span = tracer.nextSpan()
                .name("redis." + methodName)
                .start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag(TracingConstant.TAG_DB_SYSTEM, "redis");
            span.tag(TracingConstant.TAG_DB_OPERATION, methodName);

            long start = System.currentTimeMillis();
            Object result = pjp.proceed();
            span.tag(TracingConstant.TAG_DURATION_MS,
                    String.valueOf(System.currentTimeMillis() - start));
            return result;

        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
