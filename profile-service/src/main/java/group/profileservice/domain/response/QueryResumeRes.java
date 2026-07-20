package group.profileservice.domain.response;/* I love coding */

import group.dto.StudentProfile;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

//查询简历解析情况
@Data
public class QueryResumeRes {
    private String parseJobId;
    private String status;
    private Result_ result;
    @Data
    @AllArgsConstructor
    public static class Result_ {
        private StudentProfile parsedProfile;
        private List<String> missingFields;
        private Map<String, String> sourceMeta;
    }
}
