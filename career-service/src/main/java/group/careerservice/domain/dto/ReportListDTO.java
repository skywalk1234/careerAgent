package group.careerservice.domain.dto;/* I love coding */

import group.careerservice.domain.dto.RoadMappingDTO.PathRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportListDTO implements Serializable {
    private String reportId;
    private String reportTitle;
    private String status;
    private PathRef pathRef;
    private Integer version;
    private LocalDateTime generatedAt;
    private LocalDateTime updatedAt;


}