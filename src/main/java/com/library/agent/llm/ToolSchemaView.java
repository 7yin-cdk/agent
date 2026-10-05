package com.library.agent.llm;

import lombok.Data;

import java.util.List;

/**
 * 已注册工具的 schema 视图，供测评脚本做参数类型与必填校验。
 * <p>
 * 直接从工具注册表导出，避免在 Python 侧再维护一份「工具有哪些参数」的第二份真相。
 */
@Data
public class ToolSchemaView {

    /**
     * 工具名，即 {@code @Tool} 标注的 Java 方法名
     */
    private String name;

    /**
     * 访问类型：READ 只读 / WRITE 写。写工具在测评中属于安全红线
     */
    private String access;

    /**
     * 参数列表
     */
    private List<Param> params;

    /**
     * 单个参数的声明信息。
     */
    @Data
    public static class Param {

        /**
         * 参数名
         */
        private String name;

        /**
         * 参数类型：string / integer / number / boolean / array / object
         */
        private String type;

        /**
         * 是否必填
         */
        private boolean required;
    }
}
