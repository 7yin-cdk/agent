package com.library.agent.MQ.Message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagIngestMessage {
    /**
     * 文件id
     */
    private Long fileId;

    /**
     * minIo中的桶
     */
    private String bucketName;

    /**
     * 对象名
     */
    private String objectName;

    /**
     * 文件名
     */
    private String fileName;

}
