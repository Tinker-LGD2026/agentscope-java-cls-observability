package io.github.tinkerlgd2026.agentscope.cls;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class V02PublicAbiManifestTest {

    @Test
    void retainsEveryPublicJvmDescriptorFromPublishedZeroTwoJar() throws Exception {
        List<String> missing = new ArrayList<>();
        int classCount = 0;
        int memberCount = 0;
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                Objects.requireNonNull(
                                        getClass()
                                                .getResourceAsStream("/v0-2-public-abi.tsv")),
                                StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                if ("CLASS".equals(fields[0])) {
                    classCount++;
                    verifyClass(fields, missing);
                } else if ("MEMBER".equals(fields[0])) {
                    memberCount++;
                    verifyMember(fields, missing);
                }
            }
        }
        assertThat(classCount).isEqualTo(27);
        assertThat(memberCount).isEqualTo(198);
        assertThat(missing).isEmpty();
    }

    private static void verifyClass(String[] expected, List<String> missing) {
        try {
            Class<?> type = Class.forName(expected[1], false, V02PublicAbiManifestTest.class.getClassLoader());
            if (!Modifier.isPublic(type.getModifiers())) {
                missing.add(expected[1] + " is no longer public");
            }
            if (type.isInterface() != Boolean.parseBoolean(expected[2])) {
                missing.add(expected[1] + " changed class/interface kind");
            }
            if (Modifier.isFinal(type.getModifiers()) != Boolean.parseBoolean(expected[3])) {
                missing.add(expected[1] + " changed final modifier");
            }
        } catch (ClassNotFoundException exception) {
            missing.add(expected[1] + " is missing");
        }
    }

    private static void verifyMember(String[] expected, List<String> missing) {
        try {
            Class<?> owner = Class.forName(expected[1], false, V02PublicAbiManifestTest.class.getClassLoader());
            Member member = findMember(owner, expected[2], expected[3], expected[4]);
            if (member == null) {
                missing.add(expected[1] + " " + expected[2] + " " + expected[3] + expected[4]);
                return;
            }
            int modifiers = member.getModifiers();
            if (Modifier.isStatic(modifiers) != Boolean.parseBoolean(expected[5])
                    || Modifier.isFinal(modifiers) != Boolean.parseBoolean(expected[6])
                    || Modifier.isAbstract(modifiers) != Boolean.parseBoolean(expected[7])
                    || member.getDeclaringClass() != owner) {
                missing.add(expected[1] + " " + expected[3] + expected[4] + " modifiers changed");
            }
        } catch (ClassNotFoundException exception) {
            missing.add(expected[1] + " is missing");
        }
    }

    private static Member findMember(Class<?> owner, String kind, String name, String descriptor) {
        if ("FIELD".equals(kind)) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())
                        && field.getName().equals(name)
                        && descriptor(field.getType()).equals(descriptor)) {
                    return field;
                }
            }
        } else if ("CONSTRUCTOR".equals(kind)) {
            for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())
                        && executableDescriptor(constructor.getParameterTypes(), void.class)
                                .equals(descriptor)) {
                    return constructor;
                }
            }
        } else {
            for (Method method : owner.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())
                        && method.getName().equals(name)
                        && executableDescriptor(method.getParameterTypes(), method.getReturnType())
                                .equals(descriptor)) {
                    return method;
                }
            }
        }
        return null;
    }

    private static String executableDescriptor(Class<?>[] parameters, Class<?> result) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : parameters) {
            descriptor.append(descriptor(parameter));
        }
        return descriptor.append(')').append(descriptor(result)).toString();
    }

    private static String descriptor(Class<?> type) {
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        if (!type.isPrimitive()) {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("unknown primitive " + type);
    }
}
