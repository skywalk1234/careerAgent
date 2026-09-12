package group.careerservice.domain.vo;/* I love coding */

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimpleRouteVO implements Serializable {
    /**
     * 路径节点列表
     */
    private List<PathNode> pathNodes;

    /**
     * 路径节点内部类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PathNode implements Serializable{
        /**
         * 节点ID
         */
        private String id;

        /**
         * 职位ID
         */
        private String jobId;

        private String jobName;

        /**
         * 阶段类型
         */
        private String stage;
    }
}
