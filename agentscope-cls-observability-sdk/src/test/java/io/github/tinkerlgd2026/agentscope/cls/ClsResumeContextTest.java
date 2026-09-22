package io.github.tinkerlgd2026.agentscope.cls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClsResumeContextTest {

    @Test
    void preservesValidTurnId() {
        ClsResumeContext context = new ClsResumeContext("turn-123");

        assertThat(context.resumeFromTurnId()).isEqualTo("turn-123");
    }

    @Test
    void rejectsBlankOrOversizedTurnId() {
        assertThatThrownBy(() -> new ClsResumeContext(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resumeFromTurnId");
        assertThatThrownBy(() -> new ClsResumeContext("中".repeat(200)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("512");
    }
}
