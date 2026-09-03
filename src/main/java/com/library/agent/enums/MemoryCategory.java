package com.library.agent.enums;

/**
 * 长期记忆类别常量。
 * 区分永久类（画像/偏好/约束，LLM 合并去重、不设 TTL）与淘汰类（实体/经验，按容量 + 重要度×最近访问衰减淘汰）。
 */
public enum MemoryCategory {

    /** 用户画像：角色、职责、负责的集群/系统、技术栈。永久类 */
    USER_PROFILE,
    /** 偏好：回答方式/格式/详略/语言。永久类 */
    PREFERENCE,
    /** 约束：明确的操作限制（如"生产库禁止 DDL"）。永久类 */
    CONSTRAINT,
    /** 实体知识：运维对象的稳定事实（集群拓扑/表结构/任务周期）。淘汰类 */
    ENTITY,
    /** 经验案例：一次事件的问题→方案→结果，须记录 result。淘汰类 */
    EXPERIENCE
}
