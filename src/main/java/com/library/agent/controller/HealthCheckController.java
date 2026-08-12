package com.library.agent.controller;

import com.library.agent.entity.HealthCheckRecord;
import com.library.agent.healthcheck.HealthCheckRunner;
import com.library.agent.mapper.HealthCheckRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 健康巡检接口。
 * <p>
 * 提供手动触发一轮巡检与查询巡检记录的能力，便于验证和观察。
 */
@RestController
@RequestMapping("/healthcheck")
@RequiredArgsConstructor
public class HealthCheckController {

    private final HealthCheckRunner runner;
    private final HealthCheckRecordMapper recordMapper;

    /**
     * 手动触发一轮健康巡检。
     *
     * @return 巡检结果摘要，包含 runId、LLM 巡检总结与各实例详情
     */
    @PostMapping("/run")
    public Map<String, Object> run() {
        return runner.runOnce();
    }

    /**
     * 查询最近的巡检记录。
     *
     * @param limit 返回条数上限，默认 20，最大 100
     * @return 巡检记录列表
     */
    @GetMapping("/records")
    public List<HealthCheckRecord> records(@RequestParam(defaultValue = "20") int limit) {
        return recordMapper.selectRecent(Math.min(Math.max(limit, 1), 100));
    }
}
