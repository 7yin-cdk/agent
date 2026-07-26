package com.library.agent.tracing;

import io.micrometer.tracing.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 全链路追踪统一配置。
 * <p>
 * 注册 MyBatis SQL 拦截器及异步线程池 Trace 传播装饰器。
 */
@Configuration
public class TracingConfiguration implements AsyncConfigurer {

    private final Tracer tracer;

    public TracingConfiguration(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * MyBatis SQL 追踪拦截器。
     * <p>
     * MyBatis Spring Boot 自动发现所有 Interceptor 类型 Bean 并注册到 SqlSessionFactory。
     */
    @Bean
    public MyBatisSqlTracingInterceptor myBatisSqlTracingInterceptor() {
        return new MyBatisSqlTracingInterceptor(tracer);
    }

    /**
     * 带 Trace 传播的 @Async 线程池。
     * <p>
     * 实现 AsyncConfigurer 后，@EnableAsync 会使用此配置替代默认 SimpleAsyncTaskExecutor。
     */
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
