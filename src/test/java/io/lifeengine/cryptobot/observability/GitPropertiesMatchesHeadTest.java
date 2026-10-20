package io.lifeengine.cryptobot.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * KAN-1215: the git.properties baked into the build must describe the checkout that was built. From a linked
 * worktree (.worktrees/KAN-N-…) git-commit-id-maven-plugin stamped the main checkout's commit and branch instead,
 * so actuator/info and every log line's commitSha lied about the running build. Skipped where there is no
 * .git or no git binary (Docker build context), the same cases in which the build writes no git.properties.
 */
class GitPropertiesMatchesHeadTest {

    @Test
    @DisplayName("git.properties commit and branch = git rev-parse of the checkout that was built (worktrees too)")
    void gitPropertiesDescribeTheBuiltCheckout() throws Exception {
        Path basedir = Path.of(System.getProperty("user.dir"));
        assumeTrue(Files.exists(basedir.resolve(".git")), "no .git: build identity comes from build-args");
        String head = git(basedir, "rev-parse", "HEAD");
        assumeTrue(head != null, "git not available");

        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("git.properties")) {
            assertThat(in).as("classpath git.properties generated in initialize").isNotNull();
            props.load(in);
        }

        assertThat(props.getProperty("git.commit.id")).isEqualTo(head);
        assertThat(props.getProperty("git.commit.id.abbrev")).isEqualTo(head.substring(0, 7));
        String ref = git(basedir, "rev-parse", "--abbrev-ref", "HEAD");
        if (!"HEAD".equals(ref)) {
            assertThat(props.getProperty("git.branch")).isEqualTo(ref);
        }
    }

    private static String git(Path dir, String... args) throws InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return null;
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }
}
