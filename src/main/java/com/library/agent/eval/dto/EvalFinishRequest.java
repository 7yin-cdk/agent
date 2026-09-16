package com.library.agent.eval.dto;

import lombok.Data;

/**
 * 测评批次收尾请求。
 */
@Data
public class EvalFinishRequest {

    /**
     * 收尾状态：FINISHED 正常完成（默认）/ FAILED 异常中断
     */
    private String status;
}
