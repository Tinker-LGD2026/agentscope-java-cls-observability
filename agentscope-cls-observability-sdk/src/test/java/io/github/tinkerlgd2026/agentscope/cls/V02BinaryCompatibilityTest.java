package io.github.tinkerlgd2026.agentscope.cls;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V02BinaryCompatibilityTest {

    @Test
    void loadsClientBytecodeCompiledAgainstZeroTwoApi(@TempDir Path temporary) throws Exception {
        Path source =
                Path.of("src/it/v0-2-source/src/main/java/"
                        + "io/github/tinkerlgd2026/agentscope/cls/compat/V02Client.java");
        Path stubRoot = temporary.resolve("stubs");
        Path configStub =
                write(
                        stubRoot,
                        "io/github/tinkerlgd2026/agentscope/cls/ClsObservabilityConfig.java",
                        configStub());
        Path modeStub =
                write(
                        stubRoot,
                        "io/github/tinkerlgd2026/agentscope/cls/privacy/ContentCaptureMode.java",
                        "package io.github.tinkerlgd2026.agentscope.cls.privacy;"
                                + " public enum ContentCaptureMode { OFF, HASH, TRUNCATE, FULL }");
        Path classes = temporary.resolve("classes");
        Files.createDirectories(classes);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler").isNotNull();
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(null, null, null)) {
            var units =
                    files.getJavaFileObjectsFromPaths(List.of(source, configStub, modeStub));
            boolean compiled =
                    compiler.getTask(
                                    null,
                                    files,
                                    null,
                                    List.of("--release", "17", "-d", classes.toString()),
                                    null,
                                    units)
                            .call();
            assertThat(compiled).isTrue();
        }

        Path sdkPackage = classes.resolve("io/github/tinkerlgd2026/agentscope/cls");
        Files.delete(sdkPackage.resolve("ClsObservabilityConfig.class"));
        Files.delete(sdkPackage.resolve("ClsObservabilityConfig$Builder.class"));
        Files.delete(sdkPackage.resolve("privacy/ContentCaptureMode.class"));

        try (URLClassLoader loader =
                new URLClassLoader(
                        new java.net.URL[] {classes.toUri().toURL()},
                        V02BinaryCompatibilityTest.class.getClassLoader())) {
            Class<?> client =
                    Class.forName(
                            "io.github.tinkerlgd2026.agentscope.cls.compat.V02Client",
                            true,
                            loader);
            Object result = client.getMethod("run").invoke(null);
            assertThat(result).isEqualTo(4096);
        }
    }

    private static Path write(Path root, String relative, String content) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return target;
    }

    private static String configStub() {
        return """
                package io.github.tinkerlgd2026.agentscope.cls;
                import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
                import java.time.Duration;
                public final class ClsObservabilityConfig {
                  public static Builder builder() { return new Builder(); }
                  public int maxQueueSize() { return 4096; }
                  public static final class Builder {
                    public Builder contentCaptureMode(ContentCaptureMode v) { return this; }
                    public Builder reasoningCaptureMode(ContentCaptureMode v) { return this; }
                    public Builder maxContentBytes(int v) { return this; }
                    public Builder reactorContextHookEnabled(boolean v) { return this; }
                    public Builder exportScheduleDelay(Duration v) { return this; }
                    public Builder maxQueueSize(int v) { return this; }
                    public ClsObservabilityConfig build() { return new ClsObservabilityConfig(); }
                  }
                }
                """;
    }
}
