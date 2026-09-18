package com.library.agent.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.agent.tool.retry.SqlRetryClassifier;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 复制延迟与 WAL 槽位保留下钻工具。
 * <p>
 * 指标层发现"主库复制延迟字节数超阈值""从库重放延迟秒数超阈值"，或备份任务因为
 * 槽位不释放而堆积 WAL 之后，本工具给出延迟落在哪一段（发送/写入/刷盘/重放）、
 * 是哪台从库、是否有非活跃槽位导致 WAL 无法回收。
 * <p>
 * 自动识别主备：主库读 {@code pg_stat_replication}，备库读
 * {@code pg_last_wal_receive_lsn()/pg_last_wal_replay_lsn()}；备库场景不报错，
 * 只是把 replicas 留空并给出说明。
 *
 * @author 郑钦
 */
@Component
@RequiredArgsConstructor
public class ReplicationDiagnosisTool extends AbstractPostgresTool {

    private final InstanceResolver instanceResolver;

    /**
     * 获取复制状态与 WAL 槽位保留情况。
     *
     * @param instance 数据库实例地址 host:port，或巡检配置中的业务名
     * @param database 数据库名称；使用业务名时可省略
     * @return 主备状态、各从库延迟明细、槽位保留字节数的 JSON
     */
    @Tool("获取指定数据库的复制状态：自动区分主库/备库，主库返回每台从库的连接状态、同步模式、四个 LSN 位置与两两差值、write/flush/replay 延迟秒数，备库返回接收与重放 LSN 差值和重放延迟时长；同时返回复制槽列表、各槽位保留的 WAL 字节数与非活跃槽位数，用于排查复制延迟与 WAL 堆积")
    public String getReplicationStatus(
            @P("数据库实例地址 host:port，或巡检配置中的业务名（如 rag库）") String instance,
            @P(required = false, value = "数据库名称；使用业务名时可省略，默认取该实例配置的库名") String database) {

        InstanceResolver.ResolvedTarget target = instanceResolver.resolve(instance, database);
        if (target == null) {
            return errorJson("无法解析数据库实例: " + instance
                    + "。请提供 host:port 形式的地址（如 localhost:5432），或 agent.healthcheck.targets "
                    + "中已定义的业务名（如 rag库）；仅当使用业务名时可省略 database 参数");
        }

        return executeWithRetry(target.hostPort(), target.database(), "复制状态采集失败", conn -> {

            ObjectNode result = baseResult(instance, target.database());
            result.put("resolvedInstance", target.hostPort());

            boolean inRecovery = queryInRecovery(conn);
            result.put("role", inRecovery ? "standby" : "primary");

            if (inRecovery) {
                collectStandbyView(conn, result);
                result.putNull("replicaCount");
                result.set("replicas", OBJECT_MAPPER.createArrayNode());
                result.put("note", "本实例处于恢复状态（备库），不存在 pg_stat_replication 视图，"
                        + "已改用 pg_last_wal_receive_lsn/pg_last_wal_replay_lsn 计算未重放字节数与重放延迟；"
                        + "如需查看各从库明细请连接主库");
            } else {
                collectPrimaryView(conn, result);
            }

            collectReplicationSlots(conn, result);

            return OBJECT_MAPPER.writeValueAsString(result);
        });
    }

    /**
     * 判断当前实例是否处于恢复状态（即备库）。
     */
    private boolean queryInRecovery(Connection conn) throws Exception {
        String sql = "SELECT pg_is_in_recovery() AS in_recovery, "
                + "CASE WHEN pg_is_in_recovery() THEN NULL ELSE pg_current_wal_lsn()::text END AS current_lsn";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getBoolean("in_recovery");
            }
            return false;
        }
    }

    /**
     * 主库视角：逐台从库的 LSN 与延迟明细。
     */
    private void collectPrimaryView(Connection conn, ObjectNode result) {
        String sql =
                "SELECT usename, application_name, client_addr::text AS client_addr, state, sync_state, "
                        + "       sent_lsn::text AS sent_lsn, write_lsn::text AS write_lsn, "
                        + "       flush_lsn::text AS flush_lsn, replay_lsn::text AS replay_lsn, "
                        + "       pg_wal_lsn_diff(sent_lsn, write_lsn)::bigint AS sent_to_write_bytes, "
                        + "       pg_wal_lsn_diff(write_lsn, flush_lsn)::bigint AS write_to_flush_bytes, "
                        + "       pg_wal_lsn_diff(flush_lsn, replay_lsn)::bigint AS flush_to_replay_bytes, "
                        + "       pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)::bigint AS total_lag_bytes, "
                        + "       round(extract(epoch from write_lag)::numeric, 3) AS write_lag_seconds, "
                        + "       round(extract(epoch from flush_lag)::numeric, 3) AS flush_lag_seconds, "
                        + "       round(extract(epoch from replay_lag)::numeric, 3) AS replay_lag_seconds "
                        + "FROM pg_stat_replication";

        ArrayNode replicas = OBJECT_MAPPER.createArrayNode();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ObjectNode replica = OBJECT_MAPPER.createObjectNode();
                replica.put("user", rs.getString("usename"));
                replica.put("applicationName", rs.getString("application_name"));
                replica.put("clientAddr", rs.getString("client_addr"));
                replica.put("state", rs.getString("state"));
                replica.put("syncState", rs.getString("sync_state"));
                replica.put("sentLsn", rs.getString("sent_lsn"));
                replica.put("writeLsn", rs.getString("write_lsn"));
                replica.put("flushLsn", rs.getString("flush_lsn"));
                replica.put("replayLsn", rs.getString("replay_lsn"));
                putNumberOrNull(replica, "sentToWriteBytes", rs, "sent_to_write_bytes");
                putNumberOrNull(replica, "writeToFlushBytes", rs, "write_to_flush_bytes");
                putNumberOrNull(replica, "flushToReplayBytes", rs, "flush_to_replay_bytes");
                putNumberOrNull(replica, "totalLagBytes", rs, "total_lag_bytes");
                putNumberOrNull(replica, "writeLagSeconds", rs, "write_lag_seconds");
                putNumberOrNull(replica, "flushLagSeconds", rs, "flush_lag_seconds");
                putNumberOrNull(replica, "replayLagSeconds", rs, "replay_lag_seconds");
                replicas.add(replica);
            }
        } catch (Exception e) {
            SqlRetryClassifier.rethrowIfConnectionLevel(e);
            result.put("replicasError", e.getMessage());
        }

        result.put("replicaCount", replicas.size());
        result.set("replicas", replicas);
    }

    /**
     * 备库视角：接收与重放位置差值，以及基于最后一次重放事务时间戳的延迟时长。
     */
    private void collectStandbyView(Connection conn, ObjectNode result) {
        String sql =
                "SELECT pg_last_wal_receive_lsn()::text AS receive_lsn, "
                        + "       pg_last_wal_replay_lsn()::text AS replay_lsn, "
                        + "       pg_wal_lsn_diff(pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn())::bigint AS unreplayed_bytes, "
                        + "       COALESCE(round(extract(epoch from (now() - pg_last_xact_replay_timestamp()))::numeric, 3), 0) AS replay_lag_seconds";

        ObjectNode standby = OBJECT_MAPPER.createObjectNode();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                standby.put("receiveLsn", rs.getString("receive_lsn"));
                standby.put("replayLsn", rs.getString("replay_lsn"));
                putNumberOrNull(standby, "unreplayedBytes", rs, "unreplayed_bytes");
                putNumberOrNull(standby, "replayLagSeconds", rs, "replay_lag_seconds");
            }
        } catch (Exception e) {
            SqlRetryClassifier.rethrowIfConnectionLevel(e);
            standby.put("error", e.getMessage());
        }
        result.set("standby", standby);
    }

    /**
     * 复制槽保留情况。retention_bytes 为当前 WAL 位置与该槽位 restart_lsn 的距离，
     * 非活跃槽位会持续钉住 restart_lsn，导致 WAL 无法回收。
     */
    private void collectReplicationSlots(Connection conn, ObjectNode result) {
        String sql =
                "SELECT slot_name, slot_type, active, database, restart_lsn::text AS restart_lsn, "
                        + "       pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint AS retention_bytes "
                        + "FROM pg_replication_slots "
                        + "ORDER BY retention_bytes DESC NULLS LAST";

        ArrayNode slots = OBJECT_MAPPER.createArrayNode();
        long inactiveSlots = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ObjectNode slot = OBJECT_MAPPER.createObjectNode();
                slot.put("slotName", rs.getString("slot_name"));
                slot.put("slotType", rs.getString("slot_type"));
                boolean active = rs.getBoolean("active");
                slot.put("active", active);
                if (!active) {
                    inactiveSlots++;
                }
                slot.put("database", rs.getString("database"));
                slot.put("restartLsn", rs.getString("restart_lsn"));
                putNumberOrNull(slot, "retentionBytes", rs, "retention_bytes");
                slots.add(slot);
            }
            result.put("slotCount", slots.size());
            result.put("inactiveSlotCount", inactiveSlots);
        } catch (Exception e) {
            SqlRetryClassifier.rethrowIfConnectionLevel(e);
            result.put("replicationSlotsError", e.getMessage());
            result.putNull("slotCount");
            result.putNull("inactiveSlotCount");
        }
        result.set("replicationSlots", slots);
    }
}
