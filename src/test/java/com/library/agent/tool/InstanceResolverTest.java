package com.library.agent.tool;

import com.library.agent.config.HealthCheckProperties;
import com.library.agent.config.HealthCheckProperties.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * InstanceResolver 纯离线测试。
 * <p>
 * 覆盖"业务名 → host:port + 库名"与"字面地址原样透传"两种写法及各类解析失败场景，
 * 不依赖 Spring 容器与真实数据库。
 */
class InstanceResolverTest {

    private InstanceResolver resolver;

    @BeforeEach
    void setUp() {
        HealthCheckProperties properties = new HealthCheckProperties();
        properties.setTargets(List.of(buildTarget("rag库", "localhost", 5432, "rag_db")));
        resolver = new InstanceResolver(properties);
    }

    @Test
    void businessNameResolvesToHostPortAndBackfillsDatabase() {
        InstanceResolver.ResolvedTarget target = resolver.resolve("rag库", null);

        assertEquals("localhost:5432", target.hostPort());
        assertEquals("rag_db", target.database());
    }

    @Test
    void businessNameWithExplicitDatabaseOverridesConfiguredOne() {
        InstanceResolver.ResolvedTarget target = resolver.resolve("rag库", "other_db");

        assertEquals("localhost:5432", target.hostPort());
        assertEquals("other_db", target.database());
    }

    @Test
    void businessNameBlankDatabaseFallsBackToConfiguredOne() {
        InstanceResolver.ResolvedTarget target = resolver.resolve("rag库", "   ");

        assertEquals("rag_db", target.database());
    }

    @Test
    void businessNameSurroundingSpacesAreTrimmed() {
        InstanceResolver.ResolvedTarget target = resolver.resolve(" rag库 ", null);

        assertEquals("localhost:5432", target.hostPort());
        assertEquals("rag_db", target.database());
    }

    @Test
    void businessNameWithoutConfiguredPortDefaultsTo5432() {
        HealthCheckProperties properties = new HealthCheckProperties();
        properties.setTargets(List.of(buildTarget("order库", "10.0.0.5", null, "order_db")));
        InstanceResolver noPortResolver = new InstanceResolver(properties);

        assertEquals("10.0.0.5:5432", noPortResolver.resolve("order库", null).hostPort());
    }

    @Test
    void literalHostPortIsPassedThroughWithGivenDatabase() {
        InstanceResolver.ResolvedTarget target = resolver.resolve("192.168.1.100:5432", "mydb");

        assertEquals("192.168.1.100:5432", target.hostPort());
        assertEquals("mydb", target.database());
    }

    @Test
    void bareHostGetsDefaultPort() {
        InstanceResolver.ResolvedTarget target = resolver.resolve("localhost", "mydb");

        assertEquals("localhost:5432", target.hostPort());
        assertEquals("mydb", target.database());
    }

    @Test
    void unknownNameWithoutDatabaseReturnsNull() {
        /* 既不是已配置业务名，又没给库名 → 无法拼出可用连接目标 */
        assertNull(resolver.resolve("unknown库", null));
    }

    @Test
    void literalHostPortWithoutDatabaseReturnsNull() {
        assertNull(resolver.resolve("localhost:5432", null));
    }

    @Test
    void blankInstanceReturnsNull() {
        assertNull(resolver.resolve("   ", "mydb"));
        assertNull(resolver.resolve(null, "mydb"));
    }

    @Test
    void targetWithoutHostReturnsNull() {
        HealthCheckProperties properties = new HealthCheckProperties();
        properties.setTargets(List.of(buildTarget("bad库", null, 5432, "bad_db")));
        InstanceResolver badResolver = new InstanceResolver(properties);

        assertNull(badResolver.resolve("bad库", null));
    }

    @Test
    void targetWithoutDatabaseAndNoDatabaseArgumentReturnsNull() {
        HealthCheckProperties properties = new HealthCheckProperties();
        properties.setTargets(List.of(buildTarget("empty库", "localhost", 5432, null)));
        InstanceResolver emptyResolver = new InstanceResolver(properties);

        assertNull(emptyResolver.resolve("empty库", null));
    }

    @Test
    void emptyTargetsFallsBackToLiteralParsing() {
        HealthCheckProperties properties = new HealthCheckProperties();
        properties.setTargets(List.of());
        InstanceResolver emptyTargetsResolver = new InstanceResolver(properties);

        assertEquals("localhost:5432", emptyTargetsResolver.resolve("localhost", "mydb").hostPort());
    }

    /**
     * 构造一个巡检目标配置。
     */
    private Target buildTarget(String name, String host, Integer port, String database) {
        Target target = new Target();
        target.setName(name);
        target.setHost(host);
        target.setPort(port);
        target.setDatabase(database);
        return target;
    }
}
