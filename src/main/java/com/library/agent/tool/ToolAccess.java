package com.library.agent.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具访问类型注解，标注在 {@code @Tool} 方法旁，用于区分只读与写操作。
 * <p>
 * 只读工具仅查询/读取，不修改数据库状态、不产生外部副作用；
 * 写工具会修改系统状态（如重置统计）或产生外部副作用（如发送邮件）。
 * 未标注的方法默认为只读（{@link Type#READ}），保证新增工具时不会被误判为写操作。
 * <p>
 * 该注解在 {@code ToolCallingServiceImpl.registerTools} 注册时被读取并存入注册表，
 * 供参数落字校验（严格模式）按工具类型决定放行策略。
 *
 * @author 郑钦
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface ToolAccess {

    /**
     * 工具访问类型，默认只读。
     */
    Type value() default Type.READ;

    /**
     * 工具访问类型枚举。
     */
    enum Type {
        /* 只读工具：查询、读取、外部只读请求 */
        READ,
        /* 写工具：修改数据库状态或产生外部副作用 */
        WRITE
    }
}
