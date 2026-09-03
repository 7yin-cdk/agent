package com.library.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.config.LongTermMemoryProperties;
import com.library.agent.context.AgentChatContext;
import com.library.agent.entity.AgentLongTermMemory;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.llm.LlmService;
import com.library.agent.mapper.AgentLongTermMemoryMapper;
import com.library.agent.memory.dto.LongTermMemoryCandidate;
import com.library.agent.memory.dto.MemoryPageResponse;
import com.library.agent.memory.dto.MemoryUpdateRequest;
import com.library.agent.memory.dto.MemoryView;
import com.library.agent.memory.impl.LongTermMemoryServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模块三（长期记忆核心服务）验收测试 —— 确定性集成测试。
 * <p>
 * 设计：只依赖真实 PostgreSQL（本地 rag_db，需 M1 已建表）。
 * LLM 用桩固定返回（抽取/冲突消解输出写死、embedding 按文本哈希出 1536 维），
 * 因此无需 RocketMQ / Redis / 外网，结果确定、可离线运行。
 * <p>
 * 覆盖：store 落库+embedding 往返 / dedup_key 冲突消解不新增行 /
 * "记住"同步抽取入库 / 四路召回+访问计数 / 超容量淘汰 /
 * 语义检索 search（全分类向量相似 + 跳过无向量行）与管理分页。跑完自动物理删除测试数据。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LongTermMemoryAcceptanceTest {

    /* 与 application.yaml datasource 保持一致（本地 rag_db） */
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/rag_db";
    private static final String DB_USER = "zq";
    private static final String DB_PASS = "zq2892294059";

    /* 合成测试用户，避免污染真实用户记忆 */
    private static final long U_STORE = 9_200_000_001L;
    private static final long U_MERGE = 9_200_000_002L;
    private static final long U_REM = 9_200_000_003L;
    private static final long U_REC = 9_200_000_004L;
    private static final long U_REC_EMPTY = 9_200_000_005L;
    private static final long U_EVICT = 9_200_000_006L;
    private static final long U_SEARCH = 9_200_000_007L;
    private static final long U_REM_HIST = 9_200_000_008L;
    private static final long[] TEST_USERS = {
            U_STORE, U_MERGE, U_REM, U_REC, U_REC_EMPTY, U_EVICT, U_SEARCH, U_REM_HIST
    };

    private static JdbcTemplate jdbc;
    private static LongTermMemoryService service;
    private static LongTermMemoryProperties props;

    /* ==================== 上下文装配（仅 PG + 桩 LLM，不起完整 Spring 上下文） ==================== */

    @BeforeAll
    static void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASS);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        /* 手工装配 MyBatis：数据源 + 单张长期记忆 Mapper XML */
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(ds);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:mapper/AgentLongTermMemoryMapper.xml"));
        org.apache.ibatis.session.Configuration mybatisConfig = new org.apache.ibatis.session.Configuration();
        mybatisConfig.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(mybatisConfig);
        SqlSessionFactory factory = factoryBean.getObject();
        AgentLongTermMemoryMapper mapper = new SqlSessionTemplate(factory)
                .getMapper(AgentLongTermMemoryMapper.class);

        /* 配置对象：手工赋值，取值与 application.yaml agent.ltm 一致 */
        props = new LongTermMemoryProperties();
        props.setAlwaysOnLimit(5);
        props.setEntityRecallLimit(8);
        props.setVectorTopK(20);
        props.setRrfLimit(10);
        props.setRerankTopN(3);
        props.setRerankMinScore(0.3);
        props.setInjectLimit(6);
        props.getCapacity().setUserProfile(50);
        props.getCapacity().setPreference(50);
        props.getCapacity().setConstraint(50);
        props.getCapacity().setEntity(200);
        props.getCapacity().setExperience(200);

        /* 桩 LLM：chat 按 prompt 内容返回固定 JSON，embed 出确定性 1536 维向量 */
        LlmService llm = mock(LlmService.class);
        when(llm.chat(anyString())).thenAnswer(inv -> decideChat(inv.getArgument(0)));
        when(llm.embed(anyString())).thenAnswer(inv -> embedFor(inv.getArgument(0)));
        when(llm.rerank(anyString(), anyList(), anyInt(), anyDouble()))
                .thenAnswer(inv -> prefixRanks(inv.getArgument(1), inv.getArgument(2)));

        /* @Async 自代理不在本验收范围：一旦被误触即抛错暴露 */
        ObjectProvider<LongTermMemoryService> self = mock(ObjectProvider.class);
        when(self.getObject()).thenThrow(new AssertionError("异步路径不在验收范围，不应触发 selfProvider"));

        service = new LongTermMemoryServiceImpl(mapper, llm, new ObjectMapper(), props, self);
    }

    /* ==================== TC1 store：落库 + embedding 往返 + update/delete ==================== */

    @Test
    @Order(1)
    void tc1StoreEmbeddingUpdateDelete() {
        String uid = String.valueOf(U_STORE);
        cleanUser(uid);
        System.out.println("\n=== M3-TC1 store 落库 + embedding 往返 ===");

        AgentLongTermMemory m = service.store(U_STORE, candidate(
                "EXPERIENCE", "修复经验：selftestdb集群 死锁通过终止空闲事务解决(SELFTEST_EXP1)",
                "SELFTEST:EXP1", "selftestdb集群", "DB_CLUSTER", 8, 0.9,
                "selftestdb集群", "死锁"));
        assertNotNull(m, "store 应返回带 id 的实体");
        assertNotNull(m.getId(), "主键应回填");

        AgentLongTermMemory back = service.getById(U_STORE, m.getId());
        assertNotNull(back, "getById 应可查回");
        assertNotNull(back.getEmbedding(), "embedding 应为非空（桩固定 1536 维）");
        assertEquals(1536, back.getEmbedding().length, "embedding 维度应为 1536");
        System.out.println("  [PASS] 落库 id=" + m.getId() + "，embedding 1536 维往返成功");

        MemoryUpdateRequest update = new MemoryUpdateRequest();
        update.setContent("修复经验：selftestdb集群 死锁通过终止等待会话解决(SELFTEST_EXP1_v2)");
        update.setImportance(9);
        AgentLongTermMemory upd = service.update(U_STORE, m.getId(), update);
        assertNotNull(upd, "update 应返回更新后实体");
        AgentLongTermMemory updBack = service.getById(U_STORE, m.getId());
        assertTrue(updBack.getContent().contains("_v2"), "正文应更新");
        assertEquals(9, updBack.getImportance(), "重要度应更新为 9");
        assertNotNull(updBack.getEmbedding(), "改正文后 embedding 应重算");
        System.out.println("  [PASS] update 正文/重要度/重算 embedding 成功");

        service.deleteById(U_STORE, m.getId());
        assertNull(service.getById(U_STORE, m.getId()), "deleteById 后不应再查到");
        System.out.println("  [PASS] deleteById 逻辑删除成功");
    }

    /* ==================== TC2 同一 dedup_key：冲突消解不新增行 ==================== */

    @Test
    @Order(2)
    void tc2ConflictResolveNoDuplicateRow() {
        String uid = String.valueOf(U_MERGE);
        cleanUser(uid);
        System.out.println("\n=== M3-TC2 dedup_key 冲突消解（应 MERGE，不新增行） ===");

        AgentLongTermMemory first = service.store(U_MERGE, candidate(
                "USER_PROFILE", "销售核心库是 selftestdb(19c)", "SELFTEST:MERGE", null, null,
                6, 0.8));
        assertNotNull(first);

        AgentLongTermMemory second = service.store(U_MERGE, candidate(
                "USER_PROFILE", "销售核心库 selftestdb 已扩容到 RAC", "SELFTEST:MERGE", null, null,
                7, 0.9));
        assertNotNull(second, "冲突消解后仍应返回实体（不因异常置空）");

        long rows = countByDedup(uid, "SELFTEST:MERGE");
        assertEquals(1, rows, "同 dedup_key 冲突消解后应仅剩 1 行");
        String content = contentByDedup(uid, "SELFTEST:MERGE");
        assertTrue(content != null && content.contains("RAC"),
                "MERGE 后正文应为合并后的更完整表述");
        System.out.println("  [PASS] 两次入库后行数=" + rows + "，正文合并为: " + content);
    }

    /* ==================== TC3 "记住…" 同步抽取入库 ==================== */

    @Test
    @Order(3)
    void tc3RememberCommandSyncPersist() {
        String uid = String.valueOf(U_REM);
        cleanUser(uid);
        System.out.println("\n=== M3-TC3 显式\"记住\"命令同步抽取入库 ===");

        String query = "请记住，后续告警通过企业微信推送（SELFTEST_ALERT）";
        String conversationId = "selftest-conv-" + System.currentTimeMillis();
        service.postTurn(U_REM, conversationId, query,
                "好的，已记住：后续告警走企业微信推送。",
                List.of(msg("user", query)), null);

        assertEquals(1, countByDedup(uid, "SELFTEST:ALERT"),
                "记住命令应同步落库一条 PREFERENCE（等 postTurn 返回即已入库）");
        String content = contentByDedup(uid, "SELFTEST:ALERT");
        assertTrue(content != null && content.contains("企业微信"), "抽取正文应包含告警渠道");
        System.out.println("  [PASS] 同步落库 content=" + content);
    }

    /* ==================== TC3b 带历史会话时"记住"仍需抽到当前轮内容（回归 fix1） ==================== */

    @Test
    @Order(4)
    void tc3RememberWithHistoryStillExtractsCurrentTurn() {
        String uid = String.valueOf(U_REM_HIST);
        cleanUser(uid);
        System.out.println("\n=== M3-TC3b 多轮会话中\"记住\"命令应抽到当前轮内容 ===");

        /* 模拟生产：turnMessages 传的是回答前加载的历史，不含当前轮 user/assistant */
        List<AgentShortTermMemory> history = List.of(
                msg("user", "昨天 orders 库有锁等待告警吗？"),
                msg("assistant", "已排查，无锁等待，运行正常。"));
        String query = "请记住，备份策略调整为每周日全量(SELFTEST_BK)";
        service.postTurn(U_REM_HIST, "selftest-conv-hist-" + System.currentTimeMillis(),
                query, "好的，已记住备份策略。", history, null);

        assertEquals(1, countByDedup(uid, "SELFTEST:BK"),
                "当前轮\"记住\"内容应被注入抽取 prompt 并同步落库（历史存在也不漏抽）");
        System.out.println("  [PASS] 当前轮记忆在多轮历史场景下仍成功抽取");
    }

    /* ==================== TC4 四路召回 + 访问计数 / 空库不抛异常 ==================== */

    @Test
    @Order(5)
    void tc4RecallWithDataAndEmpty() {
        String uid = String.valueOf(U_REC);
        String uidEmpty = String.valueOf(U_REC_EMPTY);
        cleanUser(uid);
        cleanUser(uidEmpty);
        System.out.println("\n=== M3-TC4 四路召回 + access_count / 空库 fail-open ===");

        /* 空库召回：不应抛异常，注入空列表 */
        AgentChatContext empty = new AgentChatContext();
        empty.setUserId(U_REC_EMPTY);
        empty.setQuery("任何查询都行");
        service.recall(empty);
        assertNotNull(empty.getLongTermMemories(), "recall 后 context.longTermMemories 不应为 null");
        assertTrue(empty.getLongTermMemories().isEmpty(), "空库召回应返回空列表");
        System.out.println("  [PASS] 空库召回无异常、返回空列表");

        /* 造数据：一条 EXPERIENCE（带实体，走实体等值+向量），一条 USER_PROFILE（走 always-on） */
        AgentLongTermMemory exp = service.store(U_REC, candidate(
                "EXPERIENCE", "生产销售库 selftestdb集群 曾锁等待告警，终止空闲事务后恢复(SELFTEST_EXP4)",
                "SELFTEST:EXP4", "selftestdb集群", "DB_CLUSTER", 8, 0.9,
                "selftestdb集群", "锁等待"));
        AgentLongTermMemory profile = service.store(U_REC, candidate(
                "USER_PROFILE", "运维小王负责销售域数据库(SELFTEST_USER)", "SELFTEST:PROFILE", null, null,
                9, 0.9, "销售域"));
        assertNotNull(exp);
        assertNotNull(profile);

        AgentChatContext ctx = new AgentChatContext();
        ctx.setUserId(U_REC);
        ctx.setQuery("selftestdb集群 最近状态怎么样？");
        service.recall(ctx);

        List<AgentLongTermMemory> recalled = ctx.getLongTermMemories();
        assertNotNull(recalled, "recall 应写入 context.longTermMemories");
        assertFalse(recalled.isEmpty(), "有库召回不应为空");
        boolean hasExp = recalled.stream().anyMatch(r -> exp.getId().equals(r.getId()));
        boolean hasProfile = recalled.stream().anyMatch(r -> profile.getId().equals(r.getId()));
        assertTrue(hasExp, "实体等值/向量应召回 EXPERIENCE 经验（selftestdb集群）");
        assertTrue(hasProfile, "always-on 应召回 USER_PROFILE 画像");
        System.out.println("  [PASS] 召回 " + recalled.size() + " 条，含经验=" + hasExp + "，画像=" + hasProfile);

        /* 被召回记忆的访问计数应 +1 */
        AgentLongTermMemory expBack = service.getById(U_REC, exp.getId());
        AgentLongTermMemory profileBack = service.getById(U_REC, profile.getId());
        assertTrue(expBack.getAccessCount() != null && expBack.getAccessCount() >= 1,
                "被选中的 EXPERIENCE 访问计数应自增");
        assertTrue(profileBack.getAccessCount() != null && profileBack.getAccessCount() >= 1,
                "always-on 画像访问计数应自增");
        System.out.println("  [PASS] access_count 经验=" + expBack.getAccessCount()
                + "，画像=" + profileBack.getAccessCount());
    }

    /* ==================== TC5 超容量淘汰（EXPERIENCE） ==================== */

    @Test
    @Order(6)
    void tc5EvictionOverCapacity() {
        String uid = String.valueOf(U_EVICT);
        cleanUser(uid);
        System.out.println("\n=== M3-TC5 EXPERIENCE 超容量淘汰 ===");

        int savedCap = props.getCapacity().getExperience();
        AgentLongTermMemory low = null;
        try {
            /* 临时把 EXPERIENCE 容量压到 3，避免真实插入 200+ 条 */
            props.getCapacity().setExperience(3);

            low = service.store(U_EVICT, candidate("EXPERIENCE",
                    "经验-低重要度：偶尔记录某表重建耗时(SELFTEST_EV1)", "SELFTEST:EV1", null, null,
                    2, 0.5));
            service.store(U_EVICT, candidate("EXPERIENCE",
                    "经验-高重要度：双主库脑裂仲裁恢复流程(SELFTEST_EV2)", "SELFTEST:EV2", null, null,
                    7, 0.9));
            service.store(U_EVICT, candidate("EXPERIENCE",
                    "经验-高重要度：归档目录满的清理步骤(SELFTEST_EV3)", "SELFTEST:EV3", null, null,
                    7, 0.9));
            AgentLongTermMemory last = service.store(U_EVICT, candidate("EXPERIENCE",
                    "经验-高重要度：慢 SQL 抓取与参数调优(SELFTEST_EV4)", "SELFTEST:EV4", null, null,
                    7, 0.9));
            assertNotNull(low);
            assertNotNull(last);

            long kept = countByCategory(uid, "EXPERIENCE");
            assertTrue(kept <= 3, "超容量应被淘汰到容量内，当前=" + kept);
            assertNull(service.getById(U_EVICT, low.getId()),
                    "最不重要（importance=2）的经验应最先被淘汰");
            System.out.println("  [PASS] 淘汰后 EXPERIENCE 剩 " + kept + " 条，低重要度条目已物理删除");
        } finally {
            props.getCapacity().setExperience(savedCap);
        }
    }

    /* ==================== TC6 语义检索 search（M5 管理接口服务能力） ==================== */

    @Test
    @Order(7)
    void tc6SemanticSearchAndPage() {
        String uid = String.valueOf(U_SEARCH);
        cleanUser(uid);
        System.out.println("\n=== M5-TC6 语义检索 search + 管理分页 ===");

        /* 造 2 条 EXPERIENCE + 1 条 USER_PROFILE，均带向量 */
        service.store(U_SEARCH, candidate("EXPERIENCE",
                "迁移经验：selftestdb 大表在线重命名分两步避免锁(SELFTEST_SE1)", "SELFTEST:SE1", null, null,
                7, 0.9));
        service.store(U_SEARCH, candidate("EXPERIENCE",
                "排障经验：selftestdb 活动会话突增由慢 SQL 引发(SELFTEST_SE2)", "SELFTEST:SE2", null, null,
                7, 0.9));
        service.store(U_SEARCH, candidate("USER_PROFILE",
                "运维小王负责 selftestdb 域(SELFTEST_SE3)", "SELFTEST:SE3", null, null,
                9, 0.9));

        /* 语义检索：全分类向量相似应返回全部 3 条带向量的记忆，且每条带 similarity */
        List<MemoryView> hits = service.search(U_SEARCH, "selftestdb 大表迁移经验", 5);
        assertNotNull(hits, "search 不应返回 null");
        assertEquals(3, hits.size(), "3 条带向量记忆应全部命中（topK=5 不小于候选数）");
        assertTrue(hits.stream().allMatch(v -> v.getId() != null),
                "search 结果应回填主键");
        assertTrue(hits.stream().allMatch(v -> v.getSimilarity() != null),
                "search 应逐条填充 similarity");
        System.out.println("  [PASS] search 命中 " + hits.size() + " 条，均带 similarity="
                + hits.stream().map(v -> String.format("%.4f", v.getSimilarity())).toList());

        /* 手工插入一条 embedding 为 NULL 的 EXPERIENCE：search 应跳过（embedding IS NOT NULL） */
        jdbc.update("INSERT INTO agent_long_term_memory (user_id, category, content, keywords, is_deleted) "
                        + "VALUES (?, 'EXPERIENCE', ?, '{}', FALSE)",
                uid, "无向量经验：记录无 embedding 的历史笔记(SELFTEST_SE0)");
        List<MemoryView> hitsAfter = service.search(U_SEARCH, "selftestdb 大表迁移经验", 5);
        assertEquals(3, hitsAfter.size(), "无向量行不应进入语义检索结果");
        System.out.println("  [PASS] 无向量行被排除，search 仍为 3 条");

        /* 管理分页：category 过滤统计应含无向量行（EXPERIENCE=3），实体过滤默认全量=4 */
        MemoryPageResponse expPage = service.page(U_SEARCH, "EXPERIENCE", null, 1, 20);
        assertEquals(3, expPage.getTotal(), "EXPERIENCE 分页 total 应为 3（含无向量行）");
        MemoryPageResponse allPage = service.page(U_SEARCH, null, null, 1, 20);
        assertEquals(4, allPage.getTotal(), "全量分页 total 应为 4");
        System.out.println("  [PASS] 分页 total：EXPERIENCE=" + expPage.getTotal()
                + "，全量=" + allPage.getTotal());
    }

    @AfterAll
    static void tearDown() {
        for (long user : TEST_USERS) {
            cleanUser(String.valueOf(user));
        }
        System.out.println("\n验收完成，测试数据已清理。");
    }

    /* ==================== 桩 LLM 决策（固定返回，保证确定性） ==================== */

    private static String decideChat(String prompt) {
        if (prompt == null) {
            return "[]";
        }
        if (prompt.contains("你是数据库运维 Agent 的长期记忆抽取器")) {
            if (prompt.contains("SELFTEST_BK")) {
                return "[{\"category\":\"PREFERENCE\",\"content\":\"备份策略每周日全量(SELFTEST_BK)\","
                        + "\"keywords\":[\"备份\",\"每周日全量\"],\"importance\":6,\"confidence\":0.8,"
                        + "\"dedup_key\":\"SELFTEST:BK\"}]";
            }
            if (prompt.contains("SELFTEST_ALERT")) {
                return "[{\"category\":\"PREFERENCE\",\"content\":\"用户告警通过企业微信推送(SELFTEST_ALERT)\","
                        + "\"keywords\":[\"告警\",\"企业微信\"],\"importance\":7,\"confidence\":0.9,"
                        + "\"dedup_key\":\"SELFTEST:ALERT\"}]";
            }
            return "[]";
        }
        if (prompt.contains("两条记忆指向同一 dedup_key")) {
            return "{\"action\":\"MERGE\",\"content\":\"销售核心库 selftestdb(19c) 已扩容到 RAC(SELFTEST_MERGE)\","
                    + "\"importance\":7,\"confidence\":0.9}";
        }
        return "[]";
    }

    /* 确定性 embedding：同文本→同向量、异文本→异向量，均为 1536 维非空 */
    private static List<Float> embedFor(String text) {
        List<Float> vector = new ArrayList<>(1536);
        Random random = new Random(text == null ? 0L : text.hashCode());
        for (int i = 0; i < 1536; i++) {
            vector.add((float) (random.nextDouble() * 2 - 1));
        }
        return vector;
    }

    /* rerank 桩：RRF 融合结果已按分排序，直接取前 topN 即可保持确定性 */
    private static List<Integer> prefixRanks(List<String> docs, int topN) {
        List<Integer> ranks = new ArrayList<>();
        int size = docs == null ? 0 : docs.size();
        int limit = Math.min(topN, size);
        for (int i = 0; i < limit; i++) {
            ranks.add(i);
        }
        return ranks;
    }

    /* ==================== 测试辅助 ==================== */

    private static LongTermMemoryCandidate candidate(String category, String content, String dedupKey,
                                                     String entity, String entityType, int importance,
                                                     double confidence, String... keywords) {
        LongTermMemoryCandidate c = new LongTermMemoryCandidate();
        c.setCategory(category);
        c.setContent(content);
        c.setDedupKey(dedupKey);
        c.setEntity(entity);
        c.setEntityType(entityType);
        c.setImportance(importance);
        c.setConfidence(confidence);
        List<String> kws = new ArrayList<>();
        for (String keyword : keywords) {
            kws.add(keyword);
        }
        c.setKeywords(kws);
        return c;
    }

    private static AgentShortTermMemory msg(String role, String content) {
        AgentShortTermMemory m = new AgentShortTermMemory();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private static void cleanUser(String uid) {
        jdbc.update("DELETE FROM agent_long_term_memory WHERE user_id = ?", uid);
    }

    private static long countByDedup(String uid, String dedupKey) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM agent_long_term_memory WHERE user_id = ? AND dedup_key = ? AND is_deleted = FALSE",
                Long.class, uid, dedupKey);
        return n == null ? 0 : n;
    }

    private static long countByCategory(String uid, String category) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM agent_long_term_memory WHERE user_id = ? AND category = ? AND is_deleted = FALSE",
                Long.class, uid, category);
        return n == null ? 0 : n;
    }

    private static String contentByDedup(String uid, String dedupKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT content FROM agent_long_term_memory WHERE user_id = ? AND dedup_key = ? AND is_deleted = FALSE",
                String.class, uid, dedupKey);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
