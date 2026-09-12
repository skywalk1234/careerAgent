package group.careerservice.domain.dto;/* I love coding */

import lombok.Data;

/**
 * 修改行动方案请求体（前端在渲染后的 markdown 上 WYSIWYG 编辑，保存时转回 markdown 全文提交）。
 */
@Data
public class CareerPlanUpdateDTO {
    /** 修改后的方案 markdown 全文 */
    private String content;
}
