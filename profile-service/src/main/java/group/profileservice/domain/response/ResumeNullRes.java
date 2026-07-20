package group.profileservice.domain.response;/* I love coding */

import lombok.Data;

@Data

public class ResumeNullRes {
    private String parseJobId;
    private final String status="processing";
    private final Integer progress=99;
    private final Integer pollAfterMs=2000;
    public ResumeNullRes(String parseJobId){
        this.parseJobId=parseJobId;
    }
}
