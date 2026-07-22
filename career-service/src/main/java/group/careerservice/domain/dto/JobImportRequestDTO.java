package group.careerservice.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class JobImportRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String batchName;

    private String sourceType;

    private String sourceFile;

    private ImportOptions importOptions;

    @Data
    public static class ImportOptions implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<String> deduplicateBy;
        private Boolean normalizeSalary;
        private Boolean trimFields;
    }
}
