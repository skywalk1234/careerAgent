package group.vo;/* I love coding */
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
//细化匹配范围并重新匹配
@Data
public class MatchFilter implements Serializable {
    private Integer topN;
    private Scope scope;

    @Data
    public static class Scope implements Serializable{
        private List<String> preferredJobIds;
        private List<String> preferredJobKeywords;
        private List<String> cityIntents;
        private List<String> benefits;
        private Map<String, Integer> salaryRange;
        private Boolean includeSimilarJobs;
    }


}
