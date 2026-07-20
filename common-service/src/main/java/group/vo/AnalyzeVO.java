package group.vo;/* I love coding */

import lombok.Data;

import java.util.Map;

@Data
public class AnalyzeVO {
    private String jobId;
    private String source;
    private Boolean saveToHistory;
    private Boolean overwriteSameJob;
    private Map<String, String> sourceMeta;
}


