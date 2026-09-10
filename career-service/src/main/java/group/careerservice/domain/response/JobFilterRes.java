package group.careerservice.domain.response;/* I love coding */

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 岗位筛选条件选项。
 *
 * <p>改造后不再写死：各项都从向量库 job_detail_vector 里 DISTINCT 出来，
 * 保证下拉里出现的每个值都真能筛到岗位。原来的
 * industryTags / levels / companySizes / companyTypes 在爬虫数据里没有对应列，已去掉。
 */
@Data
public class JobFilterRes {

    private List<String> cities;
    private List<String> educationRequirements;
    /** 经验要求，如 3-5年 */
    private List<String> exps;
    /** 薪资档，如 15-30K */
    private List<String> salaryTiers;

    /** 由 JobVectorRepository#loadFilterOptions 的结果组装（键：city / edu / exp / tier） */
    public static JobFilterRes fromOptions(Map<String, List<String>> options) {
        JobFilterRes res = new JobFilterRes();
        res.setCities(orEmpty(options.get("city")));
        res.setEducationRequirements(orEmpty(options.get("edu")));
        res.setExps(orEmpty(options.get("exp")));
        res.setSalaryTiers(orEmpty(options.get("tier")));
        return res;
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
