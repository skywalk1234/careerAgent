package group.careerservice.domain.vo;/* I love coding */

import group.careerservice.domain.dto.RoadMappingDTO.ReportSections;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportVO {
    private String reportId;
    private String reportTitle;
    private String status;
    private ReportSections reportSections;
}
