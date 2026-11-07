package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text-finder atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — both directions (a match fails, a missing required line
 * fails), the file scan, {@code buildResult=UNSTABLE}, and a broken regex being a user error — written as JUnit.
 */
class TextFinderIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("a matching line in the build log fails the step")
    void failsWhenThePatternMatches() throws Exception {
        AtomResult run = atom.file("build.log", "[INFO] compiling 12 sources\n"
                        + "[ERROR] cannot resolve symbol Foo\n"
                        + "[INFO] BUILD FAILURE\n")
                .input("pattern", "ERROR|Exception")
                .input("useBuildLog", "true")
                .input("atom_build_log", "build.log")
                .run();

        run.assertRefused();
        assertEquals("failure", run.status());
        assertTrue(Integer.parseInt(run.field("matches")) >= 1,
                "the ERROR line should count as a match:\n" + run);
    }

    @Test
    @DisplayName("with failWhenMatch=false the same search is a report, not a verdict")
    void reportsWithoutFailing() throws Exception {
        AtomResult run = atom.file("build.log", "INFO all good\n")
                .input("pattern", "ERROR")
                .input("useBuildLog", "true")
                .input("failWhenMatch", "false")
                .input("atom_build_log", "build.log")
                .run();

        run.assertOk();
        assertEquals("0", run.field("matches"), "no line matches ERROR");
    }

    @Test
    @DisplayName("failWhenMissing fails when the required line is absent")
    void failsWhenTheRequiredLineIsMissing() throws Exception {
        AtomResult run = atom.file("build.log", "still running\n")
                .input("pattern", "BUILD SUCCESS")
                .input("useBuildLog", "true")
                .input("failWhenMatch", "false")
                .input("failWhenMissing", "true")
                .input("atom_build_log", "build.log")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("no line matched"),
                "the refusal must say the line was missing:\n" + run);
    }

    @Test
    @DisplayName("workspace files are searched when useBuildLog is false")
    void scansWorkspaceFiles() throws Exception {
        AtomResult run = atom.file("logs/app.txt", "ok\nDEPRECATED: old api\n")
                .input("pattern", "DEPRECATED")
                .input("useBuildLog", "false")
                .input("files", "logs/*.txt")
                .input("failWhenMatch", "false")
                .run();

        run.assertOk();
        assertEquals("1", run.field("matches"), "one line in logs/app.txt matches");
    }

    @Test
    @DisplayName("buildResult=UNSTABLE succeeds and asks for an unstable build")
    void marksTheBuildUnstable() throws Exception {
        AtomResult run = atom.file("build.log", "warning: deprecated\n")
                .input("regexp", "deprecated")
                .input("useBuildLog", "true")
                .input("buildResult", "UNSTABLE")
                .input("atom_build_log", "build.log")
                .run();

        run.assertOk();
        assertTrue(run.file("output.json").contains("\"build.result\""),
                "UNSTABLE was not requested:\n" + run);
    }

    @Test
    @DisplayName("an invalid regex is a user error, not a crash")
    void refusesABrokenRegex() throws Exception {
        AtomResult run = atom.input("pattern", "([a-z")
                .input("useBuildLog", "false")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("not a valid regular expression"),
                "the refusal must be readable:\n" + run);
    }
}
