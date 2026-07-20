package group.dto;/* I love coding */

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudentProfile implements Serializable {
    private Long id;//学生id，全局唯一，一个学生只有一个简历和画像
    private BasicInfo basicInfo;
    private List<Education> education;
    private List<WorkExperience> workExperience;
    private List<String> skills;
    private List<Certificate> certificates;
    private List<String> organizeExp;
    private List<String> projects;
    private String selfEvaluation;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BasicInfo implements Serializable{
        private String name;
        private String gender;
        private String birthday;
        private String phone;
        private String email;
        private String city;
        private List<String> jobIntention;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Education implements Serializable{
        private String school;
        private String major;
        private String degree;
        private String startDate;
        private String endDate;
        //数据库里面的字段是date，注意一下
        private String gpa;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkExperience implements Serializable{
        private String company;
        private String role;
        private String startDate;
        private String endDate;
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Certificate implements Serializable{
        private String name;
        private String date;
        private String issuer;
    }
}
