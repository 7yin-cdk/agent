package com.library.agent.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import java.io.IOException;

/**
 * 全链路追踪 HTTP 入口 Filter。
 * <p>
 * 该 Filter 在 AuthTokenFilter 之前执行（@Order(0)），负责：
 * <ul>
 *   <li>创建 HTTP 请求入口 Span</li>
 *   <li>在响应头中写入 X-Trace-Id</li>
 *   <li>将 Span 实例暂存到 request attribute 中，供后续组件使用</li>
 * </ul>
 */
@Component
@Order(0)
public class TracingFilter implements Filter {

    static final String TRACE_ID_HEADER = "X-Trace-Id";
    static final String SPAN_ATTRIBUTE = "tracing.span";

    private final Tracer tracer;

    public TracingFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        Span span = tracer.nextSpan()
                .name(TracingConstant.HTTP_PREFIX + " " + httpRequest.getMethod()
                        + " " + httpRequest.getRequestURI())
                .start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                httpResponse.setHeader(TRACE_ID_HEADER, currentSpan.context().traceId());
            }

            httpRequest.setAttribute(SPAN_ATTRIBUTE, span);

            chain.doFilter(request, response);

        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
