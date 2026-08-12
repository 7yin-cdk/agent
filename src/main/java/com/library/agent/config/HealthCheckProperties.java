package com.library.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库健康巡检配置。
 * <p>
 * 对应 application.yaml 中 agent.healthcheck 配置块，包含定时巡检开关、
 * cron 表达式、待巡检数据库实例列表以及各指标的异常判定阈值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.healthcheck")
public class HealthCheckProperties {

    /**
     * 是否启用定时巡检。
     */
    private boolean enabled = true;

    /**
     * 巡检 cron 表达式，默认每 5 分钟一次。
     */
    private String cron = "0 */5 * * * *";

    /**
     * 待巡检的数据库实例列表。
     */
    private List<Target> targets = new ArrayList<>();

    /**
     * 各指标的异常判定阈值。
     */
    private Thresholds thresholds = new Thresholds();

    /**
     * 单个待巡检数据库实例的配置。
     */
    @Data
    public static class Target {

        /**
         * 业务名称，例如 "order库"，作为工具调用时的唯一标识。
         */
        private String name;

        /**
         * 数据库实例地址（不含端口），例如 localhost。
         */
        private String host;

        /**
         * 数据库实例端口。
         */
        private Integer port;

        /**
         * 数据库名称。
         */
        private String database;

        /**
         * 连接用户名，缺省回退 spring.datasource.username。
         */
        private String username;

        /**
         * 连接密码，缺省回退 spring.datasource.password。
         */
        private String password;

        /**
         * 告警联系人邮箱列表。
         */
        private List<String> emails = new ArrayList<>();
    }

    /**
     * 指标异常判定阈值，均可按需调整。
     */
    @Data
    public static class Thresholds {

        /**
         * 缓冲池命中率低于该值视为异常，默认 95%。
         */
        private double bufferHitRateMin = 95.0;

        /**
         * 锁等待会话数超过该值视为异常，默认 0（必须告警）。
         */
        private int lockWaitingMax = 0;

        /**
         * 事务空闲未关闭连接数超过该值视为异常，默认 0（必须告警）。
         */
        private int idleInTransactionMax = 0;

        /**
         * 死元组比例超过该值视为异常，默认 10%。
         */
        private double deadTupleRatioMax = 10.0;

        /**
         * 后端写入缓冲区占比超过该值视为异常，默认 10%。
         */
        private double backendWriteRatioMax = 10.0;

        /**
         * 活跃会话数超过该值视为异常，默认 100。
         */
        private int activeSessionsMax = 100;

        /**
         * 主库视角下复制延迟字节数超过该值视为异常，默认 1MB。
         */
        private long replicationLagBytesMax = 1048576;

        /**
         * 从库视角下重放延迟秒数超过该值视为异常，默认 60 秒。
         */
        private double replicationLagSecondsMax = 60.0;
    }
}
