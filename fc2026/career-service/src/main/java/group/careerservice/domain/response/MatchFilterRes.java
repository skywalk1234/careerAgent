package group.careerservice.domain.response;/* I love coding */

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MatchFilterRes {
    private List<String> preferredJobKeywords;
    private List<String> cityIntents;
    private Map<String, Integer> salaryRange;
    private boolean includeSimilarJobs;


}
