package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@Data
@RequiredArgsConstructor
@AllArgsConstructor
public class ChatVO implements java.io.Serializable{
    private String content;
    private String provider;
    private String model;
    private Double temperature;
    private String traceId;
    private PageContext pageContext;

    @Data
    @RequiredArgsConstructor
    @AllArgsConstructor
    public static class PageContext implements java.io.Serializable{
        private String routePath;
        private String pageTitle;
        private String contextPrompt;
        private Map<String, String> data;
    }
}
