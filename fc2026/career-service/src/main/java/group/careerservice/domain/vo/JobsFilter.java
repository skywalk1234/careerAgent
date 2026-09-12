package group.careerservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 岗位筛选请求参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobsFilter {
    // 搜索关键词
    private String keyword;

    // 筛选条件
    private String city;
    private String industryTag;
    private String educationRequirement;
    private String level;
    private String companySize;
    private String companyType;

    // 排序
    @Builder.Default
    private String sortBy = "salary";
    @Builder.Default
    private String sortOrder = "desc";

    // 分页
    @Builder.Default
    private Integer page = 1;
    @Builder.Default
    private Integer pageSize = 20;
}
