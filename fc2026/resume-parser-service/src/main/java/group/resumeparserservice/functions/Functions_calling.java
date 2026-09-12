package group.resumeparserservice.functions;/* I love coding */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.common.Result;
import group.resumeparserservice.client.Career_client;
import group.resumeparserservice.client.Profile_client;
import group.vo.MatchFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class Functions_calling {
    private final Profile_client profile_client;
    private final Career_client career_client;
    private final ObjectMapper objectMapper;

    /**
     * 画像构建评估专家 - 获取学生画像详情
     */
    public String profile_eval(String userId){
        return profile_client.get_profile(userId).getData().toString();
    }

    /**
     * 人岗匹配决策专家 - 根据细化要求推荐岗位
     */
    public String match_and_recommend(String filter_json) throws JsonProcessingException {
        MatchFilter filters = new MatchFilter();
        filters.setTopN(5);
        MatchFilter.Scope scope = objectMapper.readValue(filter_json, MatchFilter.Scope.class);
        filters.setScope(scope);
        career_client.queryMatchFilters(filters);
        return "已开始进行人岗匹配并推荐岗位，请到推荐页面查看";
    }

    /**
     * 职业路径规划专家 - 路线规划
     * filter_json: {"targetJobId": "xxx", "currentSkills": "Java,Spring"}
     */
    public String route_planning(String filter_json) throws JsonProcessingException {
        Map<String, String> filter = objectMapper.readValue(filter_json, Map.class);
        String targetJobId = filter.get("targetJobId");
        if (targetJobId == null || targetJobId.isEmpty()) {
            return "错误：缺少targetJobId参数";
        }
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("targetJobId", targetJobId);
        requestBody.put("currentSkills", filter.getOrDefault("currentSkills", ""));
        Result result = career_client.autoPlan(requestBody);
        return objectMapper.writeValueAsString(result);
    }

    /**
     * 生涯报告定制专家 - 整合获取路线、生成报告、获取报告、润色报告
     * filter_json: {"action": "get_route", "pathId": "xxx"} 或 {"action": "get_route", "draftId": "xxx"}
     *              {"action": "generate_report", "pathId": "xxx"} 或 {"action": "generate_report", "draftId": "xxx"}
     *              {"action": "get_report", "reportId": "xxx"}
     *              {"action": "polish_report", "reportId": "xxx", "polishType": "professional", "focusAreas": ["skills", "experience"]}
     */
    public String report_custom(String filter_json) throws JsonProcessingException {
        Map<String, Object> filter = objectMapper.readValue(filter_json, Map.class);
        String action = (String) filter.get("action");
        
        if (action == null || action.isEmpty()) {
            return "错误：缺少action参数，可选值：get_route, generate_report, get_report, polish_report";
        }
        
        switch (action) {
            case "get_route":
                return handleGetRoute(filter);
            case "generate_report":
                return handleGenerateReport(filter);
            case "get_report":
                return handleGetReport(filter);
            case "polish_report":
                return handlePolishReport(filter);
            default:
                return "错误：未知的action值，可选值：get_route, generate_report, get_report, polish_report";
        }
    }

    private String handleGetRoute(Map<String, Object> filter) throws JsonProcessingException {
        String pathId = (String) filter.get("pathId");
        String draftId = (String) filter.get("draftId");
        if ((pathId == null || pathId.isEmpty()) && (draftId == null || draftId.isEmpty())) {
            return "错误：缺少pathId或draftId参数";
        }
        Result result = career_client.getCareerPathDetail(pathId, draftId);
        return objectMapper.writeValueAsString(result);
    }

    private String handleGenerateReport(Map<String, Object> filter) throws JsonProcessingException {
        String pathId = (String) filter.get("pathId");
        String draftId = (String) filter.get("draftId");
        if ((pathId == null || pathId.isEmpty()) && (draftId == null || draftId.isEmpty())) {
            return "错误：缺少pathId或draftId参数";
        }
        Map<String, Object> requestBody = new HashMap<>();
        if (pathId != null && !pathId.isEmpty()) {
            requestBody.put("pathId", pathId);
        }
        if (draftId != null && !draftId.isEmpty()) {
            requestBody.put("draftId", draftId);
        }
        Result result = career_client.generateCareerReport(requestBody);
        return objectMapper.writeValueAsString(result);
    }

    private String handleGetReport(Map<String, Object> filter) throws JsonProcessingException {
        String reportId = (String) filter.get("reportId");
        if (reportId == null || reportId.isEmpty()) {
            return "错误：缺少reportId参数";
        }
        Result result = career_client.queryReport(reportId);
        return objectMapper.writeValueAsString(result);
    }

    private String handlePolishReport(Map<String, Object> filter) throws JsonProcessingException {
        String reportId = (String) filter.get("reportId");
        if (reportId == null || reportId.isEmpty()) {
            return "错误：缺少reportId参数";
        }
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("polishType", filter.getOrDefault("polishType", "professional"));
        requestBody.put("focusAreas", filter.getOrDefault("focusAreas", java.util.Arrays.asList("skills", "experience")));
        Result result = career_client.createPolishTask(reportId, requestBody);
        return objectMapper.writeValueAsString(result);
    }
}
