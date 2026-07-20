package group.careerservice.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class JobImportRequestDTO {

    private String batchName;

    private String sourceType;

    private String sourceFile;

    private ImportOptions importOptions;

    @Data
    public static class ImportOptions {
        private List<String> deduplicateBy;
        private Boolean normalizeSalary;
        private Boolean trimFields;
    }
}
