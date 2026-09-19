package com.library.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.config.LongTermMemoryProperties;
import com.library.agent.llm.LlmService;
import com.library.agent.mapper.AgentLongTermMemoryMapper;
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

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 长期记忆"按类别分化淘汰"验收测试 —— 确定性集成测试。
 * <p>
 * 设计同 LongTermMemoryAcceptanceTest：只依赖真实 PostgreSQL（本地 rag_db，需已建长期记忆表），
 * LLM 用桩，不起 Spring 上下文、不需要后端与外部服务。
 * <p>
 * 被验证的规则：
 * 永久类（USER_PROFILE/PREFERENCE/CONSTRAINT）低重要度先淘汰；
 * 实体知识（ENTITY）淘汰分 = α × (最后使用距今天数 ÷ (召回次数+1)) + (1-α) × 入库距今天数；
 * 历史经验（EXPERIENCE）淘汰分 = α × 最后使用距今天数 + (1-α) × 入库距今天数。
 * 淘汰分高者先淘汰，分数并列时先入库的先淘汰；刚入库的记忆淘汰分接近 0，不会被立即淘汰。
 * <p>
 * 为精确控制 importance / access_count / last_accessed_at / created_at，
 * 本测试直接 JDBC 插入记忆行（不走 service.store），再调 evictIfNeeded 触发淘汰。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LongTermMemoryEvictionAcceptanceTest {

    /* 与 application.yaml datasource 保持一致（本地 rag_db） */
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/rag_db";
    private static final String DB_USER = "zq";
    private static final String DB_PASS = "zq2892294059";

    /* 合成测试用户，避免污染真实用户记忆 */
    private static final long U_PERM = 9_200_000_101L;
    private static final long U_PERM_TIE = 9_200_000_102L;
    private static final long U_ENTITY = 9_200_000_103L;
    private static final long U_ENTITY_FREQ = 9_200_000_104L;
    private static final long U_EXPERIENCE = 9_200_000_105L;
    private static final long U_SCORE_TIE = 9_200_000_106L;
    private static final long U_ISOLATION = 9_200_000_107L;
    private static final long U_SWEEP = 9_200_000_108L;
    private static final long U_EDGE = 9_200_000_109L;
    private static final long[] TEST_USERS = {
            U_PERM, U_PERM_TIE, U_ENTITY, U_ENTITY_FREQ, U_EXPERIENCE,
            U_SCORE_TIE, U_ISOLATION, U_SWEEP, U_EDGE
    };

    /* 与 application.yaml agent.ltm.eviction.usage-weight 一致 */
    private static final double USAGE_WEIGHT = 0.8;

    private static JdbcTemplate jdbc;
    private static LongTermMemoryService service;
    private static LongTermMemoryProperties props;

    /* ==================== 上下文装配（仅 PG + 桩 LLM，不起完整 Spring 上下文） ==================== */

    @BeforeAll
    static void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(DB_URL, DB_USER, DB_PASS);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

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

        /* 容量按 application.yaml 赋值；单个用例内临时压低，finally 恢复 */
        props = new LongTermMemoryProperties();
        props.getCapacity().setUserProfile(50);
        props.getCapacity().setPreference(50);
        props.getCapacity().setConstraint(50);
        props.getCapacity().setEntity(200);
        props.getCapacity().setExperience(200);
        props.getEviction().setUsageWeight(USAGE_WEIGHT);

        /* 本测试只做淘汰，不触发抽取/向量，LlmService 用空桩即可 */
        LlmService llm = mock(LlmService.class);

        /* @Async 自代理不在本验收范围：一旦被误触即抛错暴露 */
        ObjectProvider<LongTermMemoryService> self = mock(ObjectProvider.class);
        when(self.getObject()).thenThrow(new AssertionError("异步路径不在验收范围，不应触发 selfProvider"));

        service = new LongTermMemoryServiceImpl(mapper, llm, new ObjectMapper(), props, self);
    }

    /* ==================== TC1 永久类：低重要度先淘汰 ==================== */

    @Test
    @Order(1)
    void tc1PermanentEvictedByImportance() {
        String uid = String.valueOf(U_PERM);
        cleanUser(uid);
        System.out.println("\n=== EV-TC1 永久类（USER_PROFILE）按重要度淘汰 ===");

        int saved = props.getCapacity().getUserProfile();
        try {
            props.getCapacity().setUserProfile(3);
            insertRow(uid, "USER_PROFILE", "画像-高重要度A(SELFTEST_EP1)", 9, 0, daysAgo(4), daysAgo(4));
            insertRow(uid, "USER_PROFILE", "画像-高重要度B(SELFTEST_EP2)", 9, 0, daysAgo(3), daysAgo(3));
            insertRow(uid, "USER_PROFILE", "画像-低重要度C(SELFTEST_EP3)", 2, 0, daysAgo(2), daysAgo(2));
            insertRow(uid, "USER_PROFILE", "画像-高重要度D(SELFTEST_EP4)", 9, 0, daysAgo(1), daysAgo(1));

            service.evictIfNeeded(U_PERM, "USER_PROFILE");

            assertEquals(3, countByCategory(uid, "USER_PROFILE"), "超容量应淘汰到容量内");
            assertFalse(exists(uid, "画像-低重要度C(SELFTEST_EP3)"),
                    "永久类应淘汰重要度最低的那条");
            assertTrue(exists(uid, "画像-高重要度A(SELFTEST_EP1)")
                            && exists(uid, "画像-高重要度B(SELFTEST_EP2)")
                            && exists(uid, "画像-高重要度D(SELFTEST_EP4)"),
                    "高重要度画像应全部保留");
            System.out.println("  [PASS] 淘汰重要度最低的画像，其余 3 条保留");
        } finally {
            props.getCapacity().setUserProfile(saved);
        }
    }

    /* ==================== TC2 永久类：重要度并列时先入库的先淘汰 ==================== */

    @Test
    @Order(2)
    void tc2PermanentTieBreakByCreatedAt() {
        String uid = String.valueOf(U_PERM_TIE);
        cleanUser(uid);
        System.out.println("\n=== EV-TC2 永久类重要度并列时按入库先后兜底 ===");

        int saved = props.getCapacity().getUserProfile();
        try {
            props.getCapacity().setUserProfile(3);
            /* 故意让入库顺序与入库时间顺序相反，以排除"仅按主键排序"的干扰 */
            insertRow(uid, "USER_PROFILE", "画像-最新入库(SELFTEST_ET1)", 5, 0, null, daysAgo(1));
            insertRow(uid, "USER_PROFILE", "画像-次新入库(SELFTEST_ET2)", 5, 0, null, daysAgo(2));
            insertRow(uid, "USER_PROFILE", "画像-较旧入库(SELFTEST_ET3)", 5, 0, null, daysAgo(3));
            insertRow(uid, "USER_PROFILE", "画像-最早入库(SELFTEST_ET4)", 5, 0, null, daysAgo(4));

            service.evictIfNeeded(U_PERM_TIE, "USER_PROFILE");

            assertEquals(3, countByCategory(uid, "USER_PROFILE"), "超容量应淘汰到容量内");
            assertFalse(exists(uid, "画像-最早入库(SELFTEST_ET4)"),
                    "重要度并列时应淘汰入库最早的那条");
            assertTrue(exists(uid, "画像-最新入库(SELFTEST_ET1)"), "最新入库的画像应保留");
            System.out.println("  [PASS] 并列时淘汰入库最早的画像（与主键顺序无关）");
        } finally {
            props.getCapacity().setUserProfile(saved);
        }
    }

    /* ==================== TC3 实体：按"时间×频次折扣"淘汰分淘汰 ==================== */

    @Test
    @Order(3)
    void tc3EntityEvictedByUsageScore() {
        String uid = String.valueOf(U_ENTITY);
        cleanUser(uid);
        System.out.println("\n=== EV-TC3 实体按加权淘汰分淘汰（新入库实体受保护） ===");

        int saved = props.getCapacity().getEntity();
        try {
            props.getCapacity().setEntity(1);
            /* 淘汰分（α=0.8）：A=73.0 / B=193.0 / C=100.0 / D=1.0 */
            insertRow(uid, "ENTITY", "实体-老库高频(SELFTEST_EE_A)", 5, 100, daysAgo(2), daysAgo(365));
            insertRow(uid, "ENTITY", "实体-老库低频(SELFTEST_EE_B)", 5, 1, daysAgo(300), daysAgo(365));
            insertRow(uid, "ENTITY", "实体-中龄未召回(SELFTEST_EE_C)", 5, 0, null, daysAgo(100));
            insertRow(uid, "ENTITY", "实体-刚入库未召回(SELFTEST_EE_D)", 5, 0, null, daysAgo(1));

            service.evictIfNeeded(U_ENTITY, "ENTITY");

            assertEquals(1, countByCategory(uid, "ENTITY"), "超容量应淘汰到容量内");
            assertTrue(exists(uid, "实体-刚入库未召回(SELFTEST_EE_D)"),
                    "淘汰分最低的刚入库实体应存活（不会被紧随其后的淘汰挤掉）");
            assertFalse(exists(uid, "实体-老库低频(SELFTEST_EE_B)"), "淘汰分最高的应最先淘汰");
            assertFalse(exists(uid, "实体-中龄未召回(SELFTEST_EE_C)"), "未召回且入库较久的应被淘汰");
            assertFalse(exists(uid, "实体-老库高频(SELFTEST_EE_A)"), "淘汰分次低的老库高频也应被淘汰");
            System.out.println("  [PASS] 淘汰顺序 B→C→A，仅刚入库的 D 存活");
        } finally {
            props.getCapacity().setEntity(saved);
        }
    }

    /* ==================== TC4 实体：召回频率主导，入库更早但高频者存活 ==================== */

    @Test
    @Order(4)
    void tc4EntityFrequencyBeatsCreatedAt() {
        String uid = String.valueOf(U_ENTITY_FREQ);
        cleanUser(uid);
        System.out.println("\n=== EV-TC4 实体高频者存活（频次胜过入库时间） ===");

        int saved = props.getCapacity().getEntity();
        try {
            props.getCapacity().setEntity(1);
            /* 淘汰分：老且高频 A'=60.03，较新但从未召回 B'=100.0 */
            insertRow(uid, "ENTITY", "实体-入库300天召回50次(SELFTEST_EF_A)", 5, 50, daysAgo(2), daysAgo(300));
            insertRow(uid, "ENTITY", "实体-入库100天从未召回(SELFTEST_EF_B)", 5, 0, null, daysAgo(100));

            service.evictIfNeeded(U_ENTITY_FREQ, "ENTITY");

            assertTrue(exists(uid, "实体-入库300天召回50次(SELFTEST_EF_A)"),
                    "召回频繁的实体即使入库更早也应存活");
            assertFalse(exists(uid, "实体-入库100天从未召回(SELFTEST_EF_B)"),
                    "从未被召回且入库较久的实体应被淘汰");
            System.out.println("  [PASS] 高频实体存活，低频实体被淘汰");
        } finally {
            props.getCapacity().setEntity(saved);
        }
    }

    /* ==================== TC5 经验：按"最近使用 + 入库时间"加权淘汰分淘汰 ==================== */

    @Test
    @Order(5)
    void tc5ExperienceEvictedByRecencyScore() {
        String uid = String.valueOf(U_EXPERIENCE);
        cleanUser(uid);
        System.out.println("\n=== EV-TC5 经验按加权淘汰分淘汰 ===");

        int saved = props.getCapacity().getExperience();
        try {
            props.getCapacity().setExperience(1);
            /* 淘汰分（α=0.8）：E1=365.0 / E2=313.0 / E3=100.0 / E4=1.8 */
            insertRow(uid, "EXPERIENCE", "经验-入库365天从未用(SELFTEST_EX_E1)", 5, 0, null, daysAgo(365));
            insertRow(uid, "EXPERIENCE", "经验-入库365天300天前用过(SELFTEST_EX_E2)", 5, 1, daysAgo(300), daysAgo(365));
            insertRow(uid, "EXPERIENCE", "经验-入库100天从未用(SELFTEST_EX_E3)", 5, 0, null, daysAgo(100));
            insertRow(uid, "EXPERIENCE", "经验-入库5天1天前用过(SELFTEST_EX_E4)", 5, 1, daysAgo(1), daysAgo(5));

            service.evictIfNeeded(U_EXPERIENCE, "EXPERIENCE");

            assertEquals(1, countByCategory(uid, "EXPERIENCE"), "超容量应淘汰到容量内");
            assertTrue(exists(uid, "经验-入库5天1天前用过(SELFTEST_EX_E4)"),
                    "淘汰分最低的近期经验应存活");
            assertFalse(exists(uid, "经验-入库365天从未用(SELFTEST_EX_E1)"), "最久未使用的应最先淘汰");
            assertFalse(exists(uid, "经验-入库365天300天前用过(SELFTEST_EX_E2)"), "久未使用的应被淘汰");
            assertFalse(exists(uid, "经验-入库100天从未用(SELFTEST_EX_E3)"), "未被召回且入库较久的应被淘汰");
            System.out.println("  [PASS] 淘汰顺序 E1→E2→E3，仅近期用过的 E4 存活");
        } finally {
            props.getCapacity().setExperience(saved);
        }
    }

    /* ==================== TC6 淘汰分完全并列时，先入库的先淘汰 ==================== */

    @Test
    @Order(6)
    void tc6ScoreTieBreakByInsertOrder() {
        String uid = String.valueOf(U_SCORE_TIE);
        cleanUser(uid);
        System.out.println("\n=== EV-TC6 淘汰分并列时先入库的先淘汰 ===");

        int saved = props.getCapacity().getExperience();
        try {
            props.getCapacity().setExperience(1);
            /* 两条的入库时间与最后使用时间完全相同 → 淘汰分相同，只能靠主键（入库先后）兜底 */
            OffsetDateTime sameCreated = daysAgo(30);
            OffsetDateTime sameAccessed = daysAgo(5);
            insertRow(uid, "EXPERIENCE", "经验-先入库(SELFTEST_ETIE_1)", 5, 3, sameAccessed, sameCreated);
            insertRow(uid, "EXPERIENCE", "经验-后入库(SELFTEST_ETIE_2)", 5, 3, sameAccessed, sameCreated);

            service.evictIfNeeded(U_SCORE_TIE, "EXPERIENCE");

            assertEquals(1, countByCategory(uid, "EXPERIENCE"), "超容量应淘汰到容量内");
            assertFalse(exists(uid, "经验-先入库(SELFTEST_ETIE_1)"),
                    "淘汰分并列时应淘汰先入库的那条");
            assertTrue(exists(uid, "经验-后入库(SELFTEST_ETIE_2)"), "后入库的应保留");
            System.out.println("  [PASS] 并列时淘汰先入库的经验");
        } finally {
            props.getCapacity().setExperience(saved);
        }
    }

    /* ==================== TC7 类别隔离：各分类只按自己的规则淘汰 ==================== */

    @Test
    @Order(7)
    void tc7CategoryRulesAreIsolated() {
        String uid = String.valueOf(U_ISOLATION);
        cleanUser(uid);
        System.out.println("\n=== EV-TC7 类别隔离：实体看频次，永久类看重要度 ===");

        int savedEntity = props.getCapacity().getEntity();
        int savedProfile = props.getCapacity().getUserProfile();
        try {
            props.getCapacity().setEntity(1);
            props.getCapacity().setUserProfile(2);
            /* 实体：低重要度但高频（分 60.03） vs 高重要度但从未召回（分 100.0）
               → 淘汰分低的低重要度实体应存活，说明重要度不参与实体淘汰 */
            insertRow(uid, "ENTITY", "实体-重要度1但高频(SELFTEST_EI_A)", 1, 50, daysAgo(2), daysAgo(300));
            insertRow(uid, "ENTITY", "实体-重要度10但从未召回(SELFTEST_EI_B)", 10, 0, null, daysAgo(100));
            /* 永久类：同库下仍按重要度淘汰 */
            insertRow(uid, "USER_PROFILE", "画像-重要度1(SELFTEST_EI_C)", 1, 0, null, daysAgo(3));
            insertRow(uid, "USER_PROFILE", "画像-重要度9甲(SELFTEST_EI_D)", 9, 0, null, daysAgo(2));
            insertRow(uid, "USER_PROFILE", "画像-重要度9乙(SELFTEST_EI_E)", 9, 0, null, daysAgo(1));

            service.evictIfNeeded(U_ISOLATION, "ENTITY");
            service.evictIfNeeded(U_ISOLATION, "USER_PROFILE");

            assertTrue(exists(uid, "实体-重要度1但高频(SELFTEST_EI_A)"),
                    "实体类淘汰不看重要度：高频实体应存活");
            assertFalse(exists(uid, "实体-重要度10但从未召回(SELFTEST_EI_B)"),
                    "实体类淘汰不看重要度：从未召回且入库较久的实体应被淘汰");
            assertFalse(exists(uid, "画像-重要度1(SELFTEST_EI_C)"),
                    "永久类仍按重要度淘汰");
            assertTrue(exists(uid, "画像-重要度9甲(SELFTEST_EI_D)")
                            && exists(uid, "画像-重要度9乙(SELFTEST_EI_E)"),
                    "永久类高重要度画像应保留");
            System.out.println("  [PASS] 实体按频次淘汰、永久类按重要度淘汰，互不干扰");
        } finally {
            props.getCapacity().setEntity(savedEntity);
            props.getCapacity().setUserProfile(savedProfile);
        }
    }

    /* ==================== TC8 容量复查：绕过写时淘汰直接灌数据，仍能收敛到容量内 ==================== */

    @Test
    @Order(8)
    void tc8EvictIfNeededSweepsOverCapacity() {
        String uid = String.valueOf(U_SWEEP);
        cleanUser(uid);
        System.out.println("\n=== EV-TC8 evictIfNeeded 兜底复查超容量数据 ===");

        int saved = props.getCapacity().getEntity();
        try {
            props.getCapacity().setEntity(3);
            /* 淘汰分：e1=400 / e2=300 / e3=200 / e4=100 / e5=10.36 / e6=10 */
            insertRow(uid, "ENTITY", "实体-e1(SELFTEST_ES_1)", 5, 0, null, daysAgo(400));
            insertRow(uid, "ENTITY", "实体-e2(SELFTEST_ES_2)", 5, 1, daysAgo(300), daysAgo(300));
            insertRow(uid, "ENTITY", "实体-e3(SELFTEST_ES_3)", 5, 0, null, daysAgo(200));
            insertRow(uid, "ENTITY", "实体-e4(SELFTEST_ES_4)", 5, 0, null, daysAgo(100));
            insertRow(uid, "ENTITY", "实体-e5(SELFTEST_ES_5)", 5, 10, daysAgo(5), daysAgo(50));
            insertRow(uid, "ENTITY", "实体-e6(SELFTEST_ES_6)", 5, 0, null, daysAgo(10));

            service.evictIfNeeded(U_SWEEP, "ENTITY");

            assertEquals(3, countByCategory(uid, "ENTITY"), "应一次性淘汰到容量内");
            assertTrue(exists(uid, "实体-e4(SELFTEST_ES_4)")
                            && exists(uid, "实体-e5(SELFTEST_ES_5)")
                            && exists(uid, "实体-e6(SELFTEST_ES_6)"),
                    "保留的应是淘汰分最低的 3 条");
            assertFalse(exists(uid, "实体-e1(SELFTEST_ES_1)")
                            || exists(uid, "实体-e2(SELFTEST_ES_2)")
                            || exists(uid, "实体-e3(SELFTEST_ES_3)"),
                    "淘汰分最高的 3 条应被物理删除");
            System.out.println("  [PASS] 6 条超容量数据一次复查后收敛到 3 条");
        } finally {
            props.getCapacity().setEntity(saved);
        }
    }

    /* ==================== TC9 边界：空参数与未知分类不淘汰、不抛异常 ==================== */

    @Test
    @Order(9)
    void tc9BoundaryCasesAreSafe() {
        String uid = String.valueOf(U_EDGE);
        cleanUser(uid);
        System.out.println("\n=== EV-TC9 边界：空参数/未知分类安全 ===");

        insertRow(uid, "USER_PROFILE", "画像-边界(SELFTEST_EB_1)", 5, 0, null, daysAgo(1));
        insertRow(uid, "UNKNOWN_CATEGORY", "未知分类-边界(SELFTEST_EB_2)", 5, 0, null, daysAgo(1));

        assertDoesNotThrow(() -> service.evictIfNeeded(null, "USER_PROFILE"), "userId 为空不应抛异常");
        assertDoesNotThrow(() -> service.evictIfNeeded(U_EDGE, null), "category 为空不应抛异常");
        assertDoesNotThrow(() -> service.evictIfNeeded(U_EDGE, "UNKNOWN_CATEGORY"), "未知分类不应抛异常");

        assertTrue(exists(uid, "画像-边界(SELFTEST_EB_1)"), "空参数调用不应删除任何记忆");
        assertTrue(exists(uid, "未知分类-边界(SELFTEST_EB_2)"), "未知分类没有容量上限，不应被淘汰");
        System.out.println("  [PASS] 空参数与未知分类均安全返回，无数据被删除");
    }

    @AfterAll
    static void tearDown() {
        for (long user : TEST_USERS) {
            cleanUser(String.valueOf(user));
        }
        System.out.println("\n淘汰验收完成，测试数据已清理。");
    }

    /* ==================== 测试辅助 ==================== */

    private static OffsetDateTime daysAgo(long days) {
        return OffsetDateTime.now().minusDays(days);
    }

    /**
     * 直接插入一条记忆行（不走 service.store），以便精确控制淘汰排序依赖的四个字段：
     * importance、access_count、last_accessed_at、created_at。
     * embedding/dedup_key 留空即不参与向量召回与去重，正好只考察淘汰。
     */
    private static void insertRow(String uid, String category, String content, int importance,
                                  int accessCount, OffsetDateTime lastAccessedAt, OffsetDateTime createdAt) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO agent_long_term_memory "
                            + "(user_id, category, content, importance, access_count, last_accessed_at, created_at, is_deleted) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, FALSE)");
            ps.setString(1, uid);
            ps.setString(2, category);
            ps.setString(3, content);
            ps.setInt(4, importance);
            ps.setInt(5, accessCount);
            if (lastAccessedAt == null) {
                ps.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(6, lastAccessedAt);
            }
            ps.setObject(7, createdAt);
            return ps;
        });
    }

    private static void cleanUser(String uid) {
        jdbc.update("DELETE FROM agent_long_term_memory WHERE user_id = ?", uid);
    }

    private static long countByCategory(String uid, String category) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM agent_long_term_memory WHERE user_id = ? AND category = ? AND is_deleted = FALSE",
                Long.class, uid, category);
        return n == null ? 0 : n;
    }

    private static boolean exists(String uid, String content) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM agent_long_term_memory WHERE user_id = ? AND content = ? AND is_deleted = FALSE",
                Long.class, uid, content);
        return n != null && n > 0;
    }
}
