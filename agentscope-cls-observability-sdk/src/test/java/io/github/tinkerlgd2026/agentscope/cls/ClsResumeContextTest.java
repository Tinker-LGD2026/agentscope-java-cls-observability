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
    void enforcesExactUtf8BoundaryAndNullContract() {
        String exact = "a".repeat(512);
        assertThat(new ClsResumeContext(exact).resumeFromTurnId()).isEqualTo(exact);
        assertThatThrownBy(() -> new ClsResumeContext(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resumeFromTurnId");
        assertThatThrownBy(() -> new ClsResumeContext(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resumeFromTurnId");
        assertThatThrownBy(() -> new ClsResumeContext("a".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("512");
        assertThatThrownBy(() -> new ClsResumeContext("中".repeat(171)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("512");
    }
}
