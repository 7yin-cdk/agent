package com.library.agent.mapper;

import com.library.agent.entity.HealthCheckRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 健康巡检记录 Mapper。
 */
public interface HealthCheckRecordMapper {

    /**
     * 插入一条巡检记录。
     */
    int insert(HealthCheckRecord record);

    /**
     * 查询最近的巡检记录，按巡检时间倒序。
     *
     * @param limit 返回条数上限
     * @return 巡检记录列表
     */
    List<HealthCheckRecord> selectRecent(@Param("limit") int limit);
}
