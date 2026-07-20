package group.dto;/* I love coding */

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenSourceBonus {
    private String provider;
    private Integer totalBonus;
    private List<BonusDetail> bonusDetails;
    private String note;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BonusDetail {
        private String dimension;
        private Integer delta;
        private String reason;
    }
}
