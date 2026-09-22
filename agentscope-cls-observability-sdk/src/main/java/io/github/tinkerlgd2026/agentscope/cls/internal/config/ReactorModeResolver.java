package io.github.tinkerlgd2026.agentscope.cls.internal.config;

import io.github.tinkerlgd2026.agentscope.cls.ReactorContextMode;
import org.jspecify.annotations.Nullable;

/** Resolves the 0.3 Reactor mode together with the deprecated 0.2 boolean setting. */
public final class ReactorModeResolver {
    private ReactorModeResolver() {}

    public static ReactorContextMode resolve(
            @Nullable ReactorContextMode explicitMode, @Nullable Boolean legacyHookEnabled) {
        ReactorContextMode legacyMode =
                legacyHookEnabled == null
                        ? null
                        : legacyHookEnabled
                                ? ReactorContextMode.LEGACY_HOOK
                                : ReactorContextMode.PRIVATE;
        if (explicitMode != null && legacyMode != null && explicitMode != legacyMode) {
            throw new IllegalArgumentException(
                    "reactor context mode conflicts with legacy reactor context hook setting");
        }
        if (explicitMode != null) {
            return explicitMode;
        }
        return legacyMode == null ? ConfigBounds.DEFAULT_REACTOR_CONTEXT_MODE : legacyMode;
    }
}
