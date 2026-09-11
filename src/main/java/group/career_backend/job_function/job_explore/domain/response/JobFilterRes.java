package group.career_backend.job_function.job_explore.domain.response;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class JobFilterRes {
    private List<String> cities;
    private List<String> educationRequirements;
    private List<String> exps;
    private List<String> salaryTiers;

    public static JobFilterRes fromOptions(Map<String, List<String>> options) {
        JobFilterRes response = new JobFilterRes();
        response.setCities(orEmpty(options.get("city")));
        response.setEducationRequirements(orEmpty(options.get("edu")));
        response.setExps(orEmpty(options.get("exp")));
        response.setSalaryTiers(orEmpty(options.get("tier")));
        return response;
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
