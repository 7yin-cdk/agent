package com.library.agent.healthcheck;

import com.library.agent.config.HealthCheckProperties.Thresholds;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 指标异常判定器。
 * <p>
 * 将采集到的原始指标与配置阈值逐项比对，产出异常项列表。
 * 该判定用于规则兜底，与 LLM 的自主判断相互独立、互为补充。
 */
@Component
public class AnomalyEvaluator {

    /**
     * 比对指标与阈值，返回异常项列表。
     *
     * @param metrics    采集到的指标 Map
     * @param thresholds 异常判定阈值
     * @return 异常项列表，无异常时为空列表
     */
    public List<Anomaly> evaluate(Map<String, Object> metrics, Thresholds thresholds) {
        List<Anomaly> anomalies = new ArrayList<>();

        checkBufferHitRate(metrics, thresholds, anomalies);
        checkLockWaiting(metrics, thresholds, anomalies);
        checkIdleInTransaction(metrics, thresholds, anomalies);
        checkDeadTupleRatio(metrics, thresholds, anomalies);
        checkBackendWriteRatio(metrics, thresholds, anomalies);
        checkActiveSessions(metrics, thresholds, anomalies);
        checkReplicationLag(metrics, thresholds, anomalies);

        return anomalies;
    }

    /**
     * 校验缓冲池命中率，低于阈值视为异常。
     */
    private void checkBufferHitRate(Map<String, Object> metrics, Thresholds t, List<Anomaly> anomalies) {
        Double value = getDouble(metrics, "bufferHitRate");
        if (value != null && value >= 0 && value < t.getBufferHitRateMin()) {
            anomalies.add(new Anomaly("缓冲池命中率", value, "< " + t.getBufferHitRateMin() + "%",
                    "WARNING", "命中率过低，建议检查共享内存配置与热点查询"));
        }
    }

    /**
     * 校验锁等待会话数，超过阈值视为异常。
     */
    private void checkLockWaiting(Map<String, Object> metrics, Thresholds t, List<Anomaly> anomalies) {
        Long value = getLong(metrics, "lockWaitingSessions");
        if (value != null && value > t.getLockWaitingMax()) {
            anomalies.add(new Anomaly("锁等待会话数", value, "> " + t.getLockWaitingMax(),
                    "WARNING", "存在锁竞争，建议定位持有锁的会话并排查长事务"));
        }
    }

    /**
     * 校验事务空闲未关闭连接数，超过阈值视为必须告警的严重异常。
     */
    private void checkIdleInTransaction(Map<String, Object> metrics, Thresholds t, List<Anomaly> anomalies) {
        Long value = getLong(metrics, "idleInTransaction");
        if (value != null && value > t.getIdleInTransactionMax()) {
            anomalies.add(new Anomaly("事务空闲未关闭连接", value, "> " + t.getIdleInTransactionMax(),
                    "CRITICAL", "长期占用连接与锁，建议主动终止或优化事务提交时机"));
        }
    }

    /**
     * 校验死元组比例，超过阈值视为异常。
     */
    private void checkDeadTupleRatio(Map<String, Object> metrics, Thresholds t, List<Anomaly> anomalies) {
        Double value = getDouble(metrics, "deadTupleRatio");
        if (value != null && value >= 0 && value > t.getDeadTupleRatioMax()) {
            anomalies.add(new Anomaly("死元组比例", value, "> " + t.getDeadTupleRatioMax() + "%",
                    "WARNING", "VACUUM 回收不及时，建议对相关表执行 VACUUM"));
        }
    }

    /**
     * 校验后端写入缓冲区占比，超过阈值视为异常。
     */
    private void checkBackendWriteRatio(Map<String, Object> metrics, Thresholds t, List<Anomaly> anomalies) {
        Double value = getDouble(metrics, "backendWriteRatio");
        if (value != null && value >= 0 && value > t.getBackendWriteRatioMax()) {
            anomalies.add(new Anomaly("后端写入缓冲区占比", value, "> " + t.getBackendWriteRatioMax() + "%",
                    "WARNING", "后台写入器未能吸收写入压力，建议检查相关参数"));
        }
    }

    /**
     * 校验活跃会话数，超过阈值视为异常。
     */
    private void checkActiveSessions(Map<String, Object> metrics, Thresholds t, List<Anomaly> anomalies) {
        Long value = getLong(metrics, "activeSessions");
        if (value != null && value > t.getActiveSessionsMax()) {
            anomalies.add(new Anomaly("活跃会话数", value, "> " + t.getActiveSessionsMax(),
                    "WARNING", "数据库负载过高，建议排查慢查询与连接池配置"));
        }
    }

    /**
     * 校验复制延迟，主库按字节数、从库按秒数分别判定。
     */
    private void checkReplicationLag(Map<String, Object> metrics, Thresholds t, List<Anomaly> anomalies) {
        String role = metrics.get("replicationRole") == null ? "" : String.valueOf(metrics.get("replicationRole"));
        if ("primary".equals(role)) {
            Long bytes = getLong(metrics, "replicationLagBytes");
            if (bytes != null && bytes > t.getReplicationLagBytesMax()) {
                anomalies.add(new Anomaly("主从复制延迟", bytes, "> " + t.getReplicationLagBytesMax() + " 字节",
                        "WARNING", "从库 WAL 落后过多，建议检查网络与从库负载"));
            }
        } else if ("standby".equals(role)) {
            Double seconds = getDouble(metrics, "replicationLagSeconds");
            if (seconds != null && seconds >= 0 && seconds > t.getReplicationLagSecondsMax()) {
                anomalies.add(new Anomaly("从库重放延迟", seconds, "> " + t.getReplicationLagSecondsMax() + " 秒",
                        "WARNING", "从库重放落后过多，建议检查重放进程与磁盘 I/O"));
            }
        }
    }

    /**
     * 读取指标中的整数，值非法或小于 0（采集失败哨兵值）时返回 null。
     */
    private Long getLong(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        if (value instanceof Number number) {
            long v = number.longValue();
            return v < 0 ? null : v;
        }
        return null;
    }

    /**
     * 读取指标中的浮点数，值非法或小于 0（采集失败哨兵值）时返回 null。
     */
    private Double getDouble(Map<String, Object> metrics, String key) {
        Object value = metrics.get(key);
        if (value instanceof Number number) {
            double v = number.doubleValue();
            return v < 0 ? null : v;
        }
        return null;
    }

    /**
     * 单条异常项。
     */
    public record Anomaly(String metric, double value, String threshold, String level, String suggestion) {
    }
}
