package com.library.agent.eval;

import com.library.agent.entity.EvalCaseResult;
import com.library.agent.entity.EvalRun;
import com.library.agent.mapper.EvalCaseResultMapper;
import com.library.agent.mapper.EvalRunMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测评结果表 Mapper 验收测试 —— 确定性集成测试。
 * <p>
 * 设计：只依赖真实 PostgreSQL（本地 rag_db，需已执行 agent_eval.sql 建表），不起 Spring 上下文。
 * 手工装配 SqlSessionFactory 并挂载 EvalRunMapper.xml / EvalCaseResultMapper.xml，
 * 覆盖 XML 在编译期查不出的问题：JSONB 读写、TIMESTAMPTZ 读写、批量插入、部分更新语义。
 * <p>
 * 覆盖：批次 insert/selectByRunId 往返（JSONB + TIMESTAMPTZ + BigDecimal）/
 * updateSummary 仅更新非空字段（不误清空既有值）/ 用例 insert 与 insertBatch /
 * 五个 JSONB 列的非空往返与 null 保持 / 布尔与计数列往返。跑完自动物理删除测试数据。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvalMapperAcceptanceTest {

    /* 与 application.yaml datasource 保持一致（本地 rag_db） */
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/rag_db";
    private static final String DB_USER = "zq";
    private static final String DB_PASS = "zq2892294059";

    /* 合成批次号，避免污染真实测评数据 */
    private static final String RUN_ID = "selftest_eval_mapper";

    private static JdbcTemplate jdbc;
    private static EvalRunMapper runMapper;
    private static EvalCaseResultMapper caseMapper;

    @BeforeAll
    static void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASS);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        clean();

        /* 手工装配 MyBatis：数据源 + 两张测评结果 Mapper XML */
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(ds);
        factoryBean.setMapperLocations(resolver.getResources("classpath:mapper/Eval*Mapper.xml"));
        org.apache.ibatis.session.Configuration mybatisConfig = new org.apache.ibatis.session.Configuration();
        mybatisConfig.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(mybatisConfig);
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        runMapper = template.getMapper(EvalRunMapper.class);
        caseMapper = template.getMapper(EvalCaseResultMapper.class);
    }

    /* ==================== TC1 批次 insert / selectByRunId 往返 ==================== */

    @Test
    @Order(1)
    void tc1RunInsertSelectRoundTrip() {
        System.out.println("\n=== EvalMapper-TC1 批次落库与读回 ===");

        EvalRun run = new EvalRun();
        run.setRunId(RUN_ID);
        run.setSuiteName("CHAT");
        run.setDatasetVersion("v1_selftest");
        run.setAgentCommit("selftest");
        run.setModelVersion("deepseek");
        run.setJudgeVersion("qwen-max");
        run.setJudgePromptVersion("judge-rubric-v1");
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now().withNano(0));
        run.setTotalCases(0);
        run.setSuccessCases(0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("note", "selftest");
        run.setSummaryJson(summary);
        runMapper.insert(run);
        assertNotNull(run.getId(), "useGeneratedKeys 应回填主键");

        EvalRun back = runMapper.selectByRunId(RUN_ID);
        assertNotNull(back, "按 run_id 应能读回批次");
        assertEquals("CHAT", back.getSuiteName());
        assertEquals("v1_selftest", back.getDatasetVersion());
        assertEquals("selftest", back.getAgentCommit());
        assertEquals("judge-rubric-v1", back.getJudgePromptVersion());
        assertEquals("RUNNING", back.getStatus());
        assertEquals(0, back.getTotalCases());
        assertNotNull(back.getStartedAt(), "TIMESTAMPTZ started_at 应可读回");
        assertNotNull(back.getCreatedAt(), "TIMESTAMPTZ created_at 应可读回");
        assertNotNull(back.getSummaryJson(), "JSONB summary_json 应可读回");
        assertEquals("selftest", back.getSummaryJson().get("note"));
        System.out.println("  [PASS] 批次 id=" + back.getId() + " JSONB/TIMESTAMPTZ 往返成功");
    }

    /* ==================== TC2 updateSummary 仅更新非空字段 ==================== */

    @Test
    @Order(2)
    void tc2UpdateSummaryPartialKeepsExisting() {
        System.out.println("\n=== EvalMapper-TC2 收尾更新（部分字段） ===");

        EvalRun patch = new EvalRun();
        patch.setRunId(RUN_ID);
        patch.setStatus("FINISHED");
        patch.setFinishedAt(LocalDateTime.now().withNano(0));
        patch.setTotalCases(3);
        patch.setSuccessCases(2);
        patch.setAvgTokens(new BigDecimal("1234.56"));
        patch.setP95Tokens(new BigDecimal("2000.00"));
        patch.setAvgDurationMs(new BigDecimal("3000.00"));
        patch.setP95DurationMs(new BigDecimal("4500.00"));
        patch.setAvgQualityScore(new BigDecimal("4.125"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("taskSuccessRate", new BigDecimal("0.6667"));
        summary.put("avgLlmCallCount", new BigDecimal("2.50"));
        patch.setSummaryJson(summary);
        assertEquals(1, runMapper.updateSummary(patch), "按 run_id 应命中 1 行");

        EvalRun back = runMapper.selectByRunId(RUN_ID);
        assertEquals("FINISHED", back.getStatus());
        assertEquals(3, back.getTotalCases());
        assertEquals(2, back.getSuccessCases());
        assertEquals(0, back.getAvgTokens().compareTo(new BigDecimal("1234.56")));
        assertEquals(0, back.getP95DurationMs().compareTo(new BigDecimal("4500.00")));
        assertEquals(0, back.getAvgQualityScore().compareTo(new BigDecimal("4.125")));
        assertNotNull(back.getFinishedAt(), "finished_at 应被写入");
        Object rate = back.getSummaryJson().get("taskSuccessRate");
        assertNotNull(rate, "JSONB 覆写后应含新键");
        assertEquals(0.6667, ((Number) rate).doubleValue(), 1e-9);
        /* 部分更新语义：未传入的列必须保持原值，不被置空 */
        assertEquals("v1_selftest", back.getDatasetVersion(), "未传入的列不应被清空");
        assertEquals("deepseek", back.getModelVersion(), "未传入的列不应被清空");
        assertNotNull(back.getStartedAt(), "未传入的 started_at 不应被清空");
        System.out.println("  [PASS] 汇总指标写入成功，未传入列保持原值");
    }

    /* ==================== TC3 用例单条 insert + 批量 insertBatch + 读回 ==================== */

    @Test
    @Order(3)
    void tc3CaseInsertBatchSelect() {
        System.out.println("\n=== EvalMapper-TC3 用例落库（单条 + 批量）与读回 ===");

        EvalCaseResult c1 = new EvalCaseResult();
        c1.setRunId(RUN_ID);
        c1.setCaseId("selftest_c1");
        c1.setCategory("知识问答");
        c1.setDifficulty("EASY");
        c1.setQuery("什么是缓冲池命中率？");
        c1.setTraceId("selftest-trace-1");
        c1.setTaskSuccess(true);
        c1.setSuccessReason("ok");
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("correctness", 5);
        scores.put("completeness", 4);
        c1.setQualityScores(scores);
        c1.setQualityTotal(new BigDecimal("4.500"));
        c1.setTotalInputTokens(800);
        c1.setTotalOutputTokens(200);
        c1.setTotalTokens(1000);
        c1.setDurationMs(3000);
        c1.setLlmCallCount(2);
        c1.setToolCallCount(0);
        c1.setRawOutput("answer 1");
        caseMapper.insert(c1);
        assertNotNull(c1.getId(), "单条插入应回填主键");

        EvalCaseResult c2 = new EvalCaseResult();
        c2.setRunId(RUN_ID);
        c2.setCaseId("selftest_c2");
        c2.setCategory("性能诊断");
        c2.setDifficulty("HARD");
        c2.setQuery("诊断一下 rag 库");
        c2.setTaskSuccess(false);
        c2.setSuccessReason("trace status=ERROR");
        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("vector_hit", List.of("doc_a", "doc_b"));
        retrieval.put("rrf_rank", 3);
        c2.setRetrievalResult(retrieval);
        c2.setErrorMessage("boom");

        EvalCaseResult c3 = new EvalCaseResult();
        c3.setRunId(RUN_ID);
        c3.setCaseId("selftest_c3");
        c3.setCategory("越界闲聊");
        c3.setDifficulty("EASY");
        c3.setQuery("你好");
        c3.setTaskSuccess(true);

        assertEquals(2, caseMapper.insertBatch(List.of(c2, c3)), "批量插入应返回影响行数 2");

        List<EvalCaseResult> rows = caseMapper.selectByRunId(RUN_ID);
        assertEquals(3, rows.size(), "该批次应读回 3 条用例");

        EvalCaseResult r1 = rows.get(0);
        assertEquals("selftest_c1", r1.getCaseId());
        assertTrue(r1.getTaskSuccess());
        assertNotNull(r1.getQualityScores(), "JSONB quality_scores 应可读回");
        assertEquals(5, ((Number) r1.getQualityScores().get("correctness")).intValue());
        assertEquals(0, r1.getQualityTotal().compareTo(new BigDecimal("4.500")));
        assertNull(r1.getToolSelection(), "未写入的 JSONB 列应保持 null");
        assertNotNull(r1.getCreatedAt(), "TIMESTAMPTZ created_at 应可读回");
        assertEquals(1000, r1.getTotalTokens());

        EvalCaseResult r2 = rows.get(1);
        assertEquals("selftest_c2", r2.getCaseId());
        assertFalse(r2.getTaskSuccess());
        assertNull(r2.getQualityScores(), "未写入的 JSONB 列应保持 null");
        assertNotNull(r2.getRetrievalResult(), "嵌套 JSONB 应可读回");
        assertEquals(3, ((Number) r2.getRetrievalResult().get("rrf_rank")).intValue());
        assertEquals(2, ((List<?>) r2.getRetrievalResult().get("vector_hit")).size());
        assertEquals("boom", r2.getErrorMessage());

        EvalCaseResult r3 = rows.get(2);
        assertEquals("selftest_c3", r3.getCaseId());
        assertEquals(Boolean.FALSE, r3.getHumanChecked(), "human_checked 缺省应落为 FALSE");
        System.out.println("  [PASS] 3 条用例读回，JSONB 非空往返与 null 保持均正确");
    }

    @AfterAll
    static void tearDown() {
        clean();
        System.out.println("\n验收完成，测试数据已清理。");
    }

    private static void clean() {
        jdbc.update("DELETE FROM agent_eval_case_result WHERE run_id = ?", RUN_ID);
        jdbc.update("DELETE FROM agent_eval_run WHERE run_id = ?", RUN_ID);
    }
}
