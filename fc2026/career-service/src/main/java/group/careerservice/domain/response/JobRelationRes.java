package group.careerservice.domain.response;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobRelationRes {
    private String name;
    private Double similarity;
    private String difficulty;
}
