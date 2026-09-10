package io.github.tinkerlgd2026.agentscope.cls.demo.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.github.tinkerlgd2026.agentscope.cls.ClsInvocationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TravelDemoSettingsTest {

    @Test
    void requiresTheModelApiKey() {
        assertThatThrownBy(() -> TravelDemoSettings.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void providesSafeDefaultsAndGeneratesOneSessionForTheRun() {
        TravelDemoSettings settings =
                TravelDemoSettings.fromEnvironment(Map.of("DEEPSEEK_API_KEY", "model-key"));

        assertThat(settings.sessionId()).startsWith("travel-");
        assertThat(settings.userId()).isEqualTo("travel-demo-user");
        assertThat(settings.userName()).isEqualTo("Travel Demo User");
        assertThat(settings.city()).isEqualTo("上海");
        assertThat(settings.firstPrompt()).contains("上海").contains("3000");
        assertThat(settings.secondPrompt()).contains("4000");

        RuntimeContext first = settings.newRuntimeContext();
        RuntimeContext second = settings.newRuntimeContext();
        assertThat(first).isNotSameAs(second);
        assertThat(first.getSessionId()).isEqualTo(settings.sessionId());
        assertThat(second.getSessionId()).isEqualTo(settings.sessionId());
        assertThat(first.getUserId()).isEqualTo("travel-demo-user");
        assertThat(second.getUserId()).isEqualTo("travel-demo-user");

        ClsInvocationContext invocation = first.get(ClsInvocationContext.class);
        assertThat(invocation).isNotNull();
        assertThat(invocation.userName()).isEqualTo("Travel Demo User");
        assertThat(invocation.turnId()).isNull();
        assertThat(invocation.agentType()).isEqualTo("travel-planner");
        assertThat(invocation.entryType()).isEqualTo("java-sdk");
    }

    @Test
    void acceptsCustomerIdentityAndPromptOverrides() {
        TravelDemoSettings settings =
                TravelDemoSettings.fromEnvironment(
                        Map.of(
                                "DEEPSEEK_API_KEY", "model-key",
                                "TRAVEL_SESSION_ID", "conversation-42",
                                "TRAVEL_USER_ID", "customer-7",
                                "TRAVEL_USER_NAME", "Example User",
                                "TRAVEL_CITY", "北京",
                                "TRAVEL_PROMPT", "请安排北京一日游"));

        assertThat(settings.sessionId()).isEqualTo("conversation-42");
        assertThat(settings.userId()).isEqualTo("customer-7");
        assertThat(settings.userName()).isEqualTo("Example User");
        assertThat(settings.city()).isEqualTo("北京");
        assertThat(settings.firstPrompt()).isEqualTo("请安排北京一日游");
    }

    @Test
    void rejectsBlankCustomerIdentity() {
        assertThatThrownBy(
                        () ->
                                TravelDemoSettings.fromEnvironment(
                                        Map.of(
                                                "DEEPSEEK_API_KEY",
                                                "model-key",
                                                "TRAVEL_USER_ID",
                                                "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRAVEL_USER_ID");
    }
}
