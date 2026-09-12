package group.careerservice.domain.dto;

import lombok.Data;

@Data
public class JobImportStatusDTO {

    private String importJobId;

    private String status;

    private Integer errorCode;

    private String errorMessage;

    private ImportResult result;

    @Data
    public static class ImportResult {
        private String batchId;
        private Integer totalRows;
        private Integer insertedRows;
        private Integer updatedRows;
        private Integer skippedRows;
    }
}
