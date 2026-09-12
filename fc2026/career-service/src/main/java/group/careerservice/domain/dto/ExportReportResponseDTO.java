package group.careerservice.domain.dto;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 导出报告响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportReportResponseDTO implements Serializable {

    /**
     * 导出任务ID
     */
    private String exportJobId;

    /**
     * 导出状态：success/processing/failed
     */
    private String status;

    /**
     * 轮询间隔（毫秒）
     */
    private Integer pollAfterMs;

    /**
     * 下载URL（导出成功后）
     */
    private String downloadUrl;

    /**
     * 文件路径
     */
    private String filePath;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;
}
