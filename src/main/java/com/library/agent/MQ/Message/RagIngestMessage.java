package MQ.Message;

import lombok.Data;

@Data
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

    /**
     * 文件类型
     */
    private String contentType;
}
