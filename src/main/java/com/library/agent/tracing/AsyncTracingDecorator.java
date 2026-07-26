package com.library.agent.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步线程 Trace 上下文传播装饰器。
 * <p>
 * 在 @Async 或 CompletableFuture 切换线程时，
 * 将当前 Span 和 MDC 上下文复制到新线程。
 */
public class AsyncTracingDecorator implements TaskDecorator {

    private final Tracer tracer;

    public AsyncTracingDecorator(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        Span currentSpan = tracer.currentSpan();
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        return () -> {
            Span previousSpan = tracer.currentSpan();
            try {
                if (currentSpan != null) {
                    try (Tracer.SpanInScope ignored = tracer.withSpan(currentSpan)) {
                        if (mdcContext != null) {
                            MDC.setContextMap(mdcContext);
                        }
                        runnable.run();
                    }
                } else {
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    }
                    runnable.run();
                }
            } finally {
                if (previousSpan != null) {
                    tracer.withSpan(previousSpan);
                } else if (currentSpan != null) {
                    /* clear MDC to avoid leaking */
                    MDC.clear();
                }
            }
        };
    }
}
