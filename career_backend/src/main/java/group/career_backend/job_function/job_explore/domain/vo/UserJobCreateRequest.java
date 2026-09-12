package group.career_backend.job_function.job_explore.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class UserJobCreateRequest {
    private String title;
    private String company;
    private String city;
    private String salary;
    private Double salaryMin;
    private Double salaryMax;
    private String salaryUnit;
    private Double avg;
    private String tier;
    private String exp;
    private String edu;
    private List<String> categories;
    private List<String> keywords;
    private String url;
    private String content;
}
