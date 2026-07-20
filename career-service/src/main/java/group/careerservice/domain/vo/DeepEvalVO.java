package group.careerservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DeepEvalVO implements Serializable {
    private String pathName;
    private List<SimpleRouteVO.PathNode> pathNodes;
}
