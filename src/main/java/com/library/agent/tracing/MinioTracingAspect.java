package com.library.agent.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储追踪切面。
 * <p>
 * 拦截 MinioClient 的 putObject、getObject、bucketExists、makeBucket 等操作，
 * 记录 bucket 名称和对象名（从方法参数中提取）。
 */
@Aspect
@Component
public class MinioTracingAspect {

    private final Tracer tracer;

    public MinioTracingAspect(Tracer tracer) {
        this.tracer = tracer;
    }

    @Around("execution(* io.minio.MinioClient.putObject(..))")
    public Object tracePutObject(ProceedingJoinPoint pjp) throws Throwable {
        return traceMinioCall(pjp, "minio.putObject");
    }

    @Around("execution(* io.minio.MinioClient.getObject(..))")
    public Object traceGetObject(ProceedingJoinPoint pjp) throws Throwable {
        return traceMinioCall(pjp, "minio.getObject");
    }

    @Around("execution(* io.minio.MinioClient.bucketExists(..))")
    public Object traceBucketExists(ProceedingJoinPoint pjp) throws Throwable {
        return traceMinioCall(pjp, "minio.bucketExists");
    }

    @Around("execution(* io.minio.MinioClient.makeBucket(..))")
    public Object traceMakeBucket(ProceedingJoinPoint pjp) throws Throwable {
        return traceMinioCall(pjp, "minio.makeBucket");
    }

    private Object traceMinioCall(ProceedingJoinPoint pjp, String spanName) throws Throwable {
        Span span = tracer.nextSpan()
                .name(spanName)
                .start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag(TracingConstant.TAG_DB_SYSTEM, "minio");

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
