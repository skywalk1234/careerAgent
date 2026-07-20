package group.careerservice.domain.response;/* I love coding */

import lombok.Data;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位筛选条件选项
 */
@Data
public class JobFilterRes {

    private List<String> cities;
    private List<String> industryTags;
    private List<String> levels;
    private List<String> companySizes;
    private List<String> companyTypes;
    private List<String> educationRequirements;

    /**
     * 获取写死的筛选条件数据
     */
    public static JobFilterRes getDefaultOptions() {
        JobFilterRes options = new JobFilterRes();

        options.setCities(Arrays.asList(
                "西安", "北京", "上海", "深圳"
        ));

        options.setIndustryTags(Arrays.asList(
                "互联网", "人工智能", "软件服务", "数字化服务", "大模型应用"
        ));

        options.setLevels(Arrays.asList(
                "junior", "middle", "senior"
        ));

        options.setCompanySizes(Arrays.asList(
                "100-499人", "500-999人", "1000+人"
        ));

        options.setCompanyTypes(Arrays.asList(
                "已上市", "未融资", "A轮融资", "B轮融资", "C轮融资"
        ));

        options.setEducationRequirements(Arrays.asList(
                "大专", "本科", "硕士", "博士", "学历不限"
        ));

        return options;
    }



}
