package group.resumeparserservice.domain.enum_;/* I love coding */

import com.fasterxml.jackson.databind.annotation.EnumNaming;


public enum StepStatus {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    SKIPPED("skipped");
    private final String value;

    StepStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
