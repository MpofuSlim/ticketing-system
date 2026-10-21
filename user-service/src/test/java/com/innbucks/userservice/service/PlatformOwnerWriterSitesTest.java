package com.innbucks.userservice.service;

import com.innbucks.userservice.entity.User;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code users.platform_owner} (V52) has exactly one writer:
 * {@code UserRepository.markPlatformOwner}, called only by
 * {@code DataInitializer}. Ownership is what lets an account promote and demote
 * SUPER_ADMINs, so a second writer — or an entity save that could carry the
 * flag — would be a way to mint the one power a deputy must never have. Same
 * shape as {@link TokenVersionBumpSitesTest}.
 */
class PlatformOwnerWriterSitesTest {

    private static final Path MAIN = Paths.get("src", "main", "java");
    private static final Pattern WRITE = Pattern.compile("\\.markPlatformOwner\\s*\\(|platform_owner\\s*=\\s*TRUE",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> ALLOWED = List.of("DataInitializer.java", "UserRepository.java");

    @Test
    void onlyTheSeederStampsTheOwner() throws IOException {
        assertThat(Files.isDirectory(MAIN))
                .as("run from the user-service module directory (surefire's default)")
                .isTrue();
        List<String> offenders = new ArrayList<>();
        List<String> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
                    if (!WRITE.matcher(line).find()) continue;
                    String name = file.getFileName().toString();
                    if (ALLOWED.contains(name)) {
                        sites.add(name);
                    } else {
                        offenders.add(MAIN.relativize(file) + ":" + (i + 1) + "  " + line);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("users.platform_owner is stamped by DataInitializer through UserRepository.markPlatformOwner only")
                .isEmpty();
        // Guards the guard: both allowed sites still exist, so the exclusion covers something.
        assertThat(sites).contains("DataInitializer.java", "UserRepository.java");
    }

    @Test
    void theEntityColumnCannotBeWrittenByASave() throws NoSuchFieldException {
        Column column = User.class.getDeclaredField("platformOwner").getAnnotation(Column.class);
        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("platform_owner");
        assertThat(column.insertable()).as("a new row must never be created as the owner").isFalse();
        assertThat(column.updatable()).as("a stale or forged save must never move ownership").isFalse();
    }

    @Test
    void theEntityHasNoSetterForIt() {
        assertThat(Arrays.stream(User.class.getMethods()).map(Method::getName))
                .doesNotContain("setPlatformOwner");
    }
}
