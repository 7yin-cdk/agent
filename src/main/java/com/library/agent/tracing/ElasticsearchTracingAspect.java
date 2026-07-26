package com.library.agent.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 操作追踪切面。
 * <p>
 * 拦截 KeywordSearchService 的搜索和索引方法，
 * 为每次 ES 交互创建子 Span。
 */
@Aspect
@Component
public class ElasticsearchTracingAspect {

    private final Tracer tracer;

    public ElasticsearchTracingAspect(Tracer tracer) {
        this.tracer = tracer;
    }

    @Pointcut("execution(* com.library.agent.es.service.KeywordSearchService.*(..))")
    public void esOperation() {
    }

    @Around("esOperation()")
    public Object traceEsOperation(ProceedingJoinPoint pjp) throws Throwable {
        String methodName = pjp.getSignature().getName();
        Span span = tracer.nextSpan()
                .name("elasticsearch." + methodName)
                .start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag(TracingConstant.TAG_DB_SYSTEM, "elasticsearch");
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
