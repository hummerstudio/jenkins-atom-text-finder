package com.jenkinschina.atom.textfinder;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * text-finder as an atom: search the build log or workspace files and decide the build result.
 *
 * <p>Two directions matter and both are supported: fail when a pattern appears (ERROR, Exception) and fail when it
 * does not (a required line such as "BUILD SUCCESS" from a wrapped tool).
 */
public class TextFinderAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String spec = context.getParam("pattern", "");
        if (spec.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "pattern is required");
        }

        List<Pattern> patterns = new ArrayList<>();
        try {
            for (String part : spec.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    patterns.add(Pattern.compile(trimmed));
                }
            }
        } catch (PatternSyntaxException e) {
            return AtomResult.error(ErrorType.USER, 40002, "not a valid regular expression: " + e.getDescription()
                    + " at index " + e.getIndex());
        }

        boolean useLog = context.getBooleanParam("useBuildLog", true);
        List<Hit> hits = new ArrayList<>();
        int scanned = 0;

        if (useLog) {
            String text = scanLog(context, patterns, hits);
            scanned = text.isEmpty() ? 0 : text.split("\n").length;
        }
        String files = context.getParam("files", "");
        if (!files.isBlank()) {
            for (Path file : find(context.getWorkspace(), files)) {
                scanned += scanFile(file, patterns, hits);
            }
        }

        boolean found = !hits.isEmpty();
        boolean failWhenMatch = context.getBooleanParam("failWhenMatch", true);
        boolean failWhenMissing = context.getBooleanParam("failWhenMissing", false);

        context.log("[text-finder] " + (found ? hits.size() + " match(es)" : "no match")
                + " over " + scanned + " line(s), patterns: " + spec);
        for (int i = 0; i < Math.min(hits.size(), 20); i++) {
            context.log("[text-finder] " + hits.get(i));
        }

        AtomResult result = AtomResult.success()
                .data("matches", String.valueOf(hits.size()))
                .data("linesScanned", String.valueOf(scanned));
        if (found) {
            result.data("firstMatch", hits.get(0).text);
        }

        if ((found && failWhenMatch) || (!found && failWhenMissing)) {
            String why = found
                    ? "found " + hits.size() + " match(es): " + hits.get(0)
                    : "no line matched " + spec + ", which the job requires";
            return AtomResult.failure(why)
                    .data("matches", String.valueOf(hits.size()))
                    .data("linesScanned", String.valueOf(scanned));
        }
        return result;
    }

    private static String scanLog(AtomContext context, List<Pattern> patterns, List<Hit> hits) {
        if (!context.hasBuildLog()) {
            return "";
        }
        String text;
        try {
            text = context.buildLog();
        } catch (Exception e) {
            return "";
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            match("<build log>", i + 1, lines[i], patterns, hits);
        }
        return text;
    }

    private static int scanFile(Path file, List<Pattern> patterns, List<Hit> hits) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return 0;
        }
        for (int i = 0; i < lines.size(); i++) {
            match(file.getFileName().toString(), i + 1, lines.get(i), patterns, hits);
        }
        return lines.size();
    }

    private static void match(String source, int line, String text, List<Pattern> patterns, List<Hit> hits) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String clipped = text.strip();
                if (clipped.length() > 200) {
                    clipped = clipped.substring(0, 200) + "…";
                }
                hits.add(new Hit(source, line, matcher.group(), clipped));
                return; // one hit per line is enough to explain the decision
            }
        }
    }

    private static List<Path> find(Path workspace, String spec) throws IOException {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:"
                        + (trimmed.contains("/") ? trimmed : "**/" + trimmed)));
            }
        }
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(workspace, 10)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> matchers.stream().anyMatch(m -> m.matches(workspace.relativize(path)) || m.matches(java.nio.file.Path.of("_").resolve(workspace.relativize(path)))
                            || m.matches(path.getFileName())))
                    .sorted()
                    .limit(200)
                    .forEach(found::add);
        }
        return found;
    }

    private record Hit(String source, int line, String matched, String text) {
        @Override
        public String toString() {
            return source + ":" + line + " [" + matched + "] " + text;
        }
    }
}
