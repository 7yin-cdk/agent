package com.library.agent.tracing;

import io.micrometer.tracing.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步线程池 Trace 传播配置。
 * <p>
 * 仅保留异步任务的 traceId 传播能力，不注册任何数据库/缓存层面的拦截器。
 */
@Configuration
public class TracingConfiguration implements AsyncConfigurer {

    private final Tracer tracer;

    public TracingConfiguration(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-trace-");
        executor.setTaskDecorator(asyncTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Bean
    public TaskDecorator asyncTaskDecorator() {
        return new AsyncTracingDecorator(tracer);
    }
}
