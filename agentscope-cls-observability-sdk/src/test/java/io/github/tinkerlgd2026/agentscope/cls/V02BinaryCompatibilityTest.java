package io.github.tinkerlgd2026.agentscope.cls;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V02BinaryCompatibilityTest {
    private static final Path CLIENT_SOURCE =
            Path.of(
                    "src/it/v0-2-source/src/main/java/"
                            + "io/github/tinkerlgd2026/agentscope/cls/compat/V02Client.java");
    private static final Path V02_CONFIG_SOURCE =
            Path.of(
                    "src/it/v0-2-binary/api-source/"
                            + "io/github/tinkerlgd2026/agentscope/cls/ClsObservabilityConfig.java");
    private static final Path V02_MODE_SOURCE =
            Path.of(
                    "src/it/v0-2-binary/api-source/"
                            + "io/github/tinkerlgd2026/agentscope/cls/privacy/ContentCaptureMode.java");

    @Test
    void taggedSourceSnapshotsMatchRecordedProvenance() throws Exception {
        assertThat(sha256(V02_CONFIG_SOURCE))
                .isEqualTo("0be61c097e0be4b523a35af2c694e4367d8ac29d8692592425d085dc95044d6e");
        assertThat(sha256(V02_MODE_SOURCE))
                .isEqualTo("80acc273a49b4b0ed8981ce479616ae11d8d88ae5b8e6962c8414eaae1a70279");
    }

    @Test
    void compilesZeroTwoSourceClientAgainstCurrentSdk(@TempDir Path temporary) throws Exception {
        Path classes = temporary.resolve("current-classes");
        compile(List.of(CLIENT_SOURCE), classes);
    }

    @Test
    void loadsClientBytecodeCompiledAgainstTaggedZeroTwoApi(@TempDir Path temporary)
            throws Exception {
        Path classes = temporary.resolve("v02-classes");
        compile(List.of(CLIENT_SOURCE, V02_CONFIG_SOURCE, V02_MODE_SOURCE), classes);

        Path sdkPackage = classes.resolve("io/github/tinkerlgd2026/agentscope/cls");
        Files.delete(sdkPackage.resolve("ClsObservabilityConfig.class"));
        Files.delete(sdkPackage.resolve("ClsObservabilityConfig$Builder.class"));
        Files.delete(sdkPackage.resolve("ClsObservabilityConfig$TransportMode.class"));
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

    private static String sha256(Path source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(source)));
    }

    private static void compile(List<Path> sources, Path classes) throws Exception {
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler").isNotNull();
        try (StandardJavaFileManager files =
                compiler.getStandardFileManager(null, null, null)) {
            var units = files.getJavaFileObjectsFromPaths(sources);
            boolean compiled =
                    compiler.getTask(
                                    null,
                                    files,
                                    null,
                                    List.of(
                                            "--release",
                                            "17",
                                            "-classpath",
                                            System.getProperty("java.class.path"),
                                            "-d",
                                            classes.toString()),
                                    null,
                                    units)
                            .call();
            assertThat(compiled).isTrue();
        }
    }
}
