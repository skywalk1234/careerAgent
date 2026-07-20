package group.careerservice.domain.dto.RoadMappingDTO;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EditingMeta implements Serializable {
    private Integer version;
    private String lastEditedAt;
    private String lastEditedBy;
}
