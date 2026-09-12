package group.careerservice.domain.dto;/* I love coding */

import group.careerservice.domain.dto.RoadMappingDTO.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 报告DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportDTO implements Serializable {

    private String reportId;
    private String reportTitle;
    private String templateVersion;
    private String status;
    private PathRef pathRef;
    private ProfileSnapshot profileSnapshot;
    private EvaluationSnapshot evaluationSnapshot;
    private ReportSections reportSections;
    private EditingMeta editingMeta;
    private LocalDateTime generatedAt;
    private LocalDateTime updatedAt;
}
