package com.library.agent.eval.dto;

import lombok.Data;

/**
 * Judge 输入中的单条工具调用记录。
 * <p>
 * 只承载评分所需的要素：调了哪个工具、传了什么参数、成功与否。
 * 工具输出正文对评分帮助有限且可能极长，故不纳入。
 */
@Data
public class EvalJudgeToolCall {

    /**
     * 工具名（对应 {@code @Tool} 方法名）
     */
    private String toolName;

    /**
     * 入参 JSON 文本；为空表示该工具无入参
     */
    private String toolInput;

    /**
     * 是否执行成功；null 表示评测侧未采集到执行结果
     */
    private Boolean success;
}
