package com.library.agent.tool.retry;

/**
 * 数据库连接级瞬时故障的包装异常。
 * <p>
 * 工具内部的单项/单区块 catch 会把连接级故障（连接已关闭、后端 I/O 中断、服务端重启中、
 * 连接池耗尽等）包成本异常上抛，让外层的重试内核能够识别并重试；数据级故障
 * （SQL 语法错误、表不存在、权限不足等）不走本异常，仍由内层降级为局部错误节点。
 * <p>
 * 选用非受检异常的原因：抛出点所在的方法（各工具的私有采集方法）均未声明 throws，
 * 若用受检异常会迫使一连串方法签名变更。
 *
 * @author 郑钦
 */
public class DbTransientException extends RuntimeException {

    /**
     * @param message 异常说明，通常直接沿用底层驱动异常的消息以保留原始错误文本
     * @param cause   被包装的底层异常，分类器会沿 cause 链继续判定
     */
    public DbTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
