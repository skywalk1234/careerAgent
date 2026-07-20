package group.careerservice.domain.dto;/* I love coding */

import lombok.Data;

import java.io.Serializable;

/**
 * 导出报告请求DTO
 */
@Data
public class ExportReportRequestDTO implements Serializable {

    /**
     * 导出格式：pdf
     */
    private String format;

    /**
     * 是否包含封面
     */
    private Boolean includeCover;

    /**
     * 是否包含时间戳
     */
    private Boolean includeTimestamp;

    /**
     * PDF模板配置
     */
    private PdfTemplateConfig pdfTemplate;

    @Data
    public static class PdfTemplateConfig implements Serializable {
        private CoverConfig cover;
        private HeaderConfig header;
        private FooterConfig footer;
        private PaginationConfig pagination;
    }

    @Data
    public static class CoverConfig implements Serializable {
        private Boolean enabled;
        private String title;
        private String subtitle;
    }

    @Data
    public static class HeaderConfig implements Serializable {
        private Boolean enabled;
        private String text;
    }

    @Data
    public static class FooterConfig implements Serializable {
        private Boolean enabled;
        private String text;
    }

    @Data
    public static class PaginationConfig implements Serializable {
        private Boolean enabled;
        private String format;
    }
}
