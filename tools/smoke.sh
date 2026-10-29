#!/usr/bin/env bash
# text-finder both ways: a match fails the build, a missing required line also fails it.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

# 1. a pattern in the build log fails the atom (default failWhenMatch=true)
dir="$(mktemp -d)"
cat >"$dir/build.log" <<'LOG'
[INFO] compiling 12 sources
[ERROR] cannot resolve symbol Foo
[INFO] BUILD FAILURE
LOG
printf '{"pattern": "ERROR|Exception", "useBuildLog": "true"}' >"$dir/input.json"
run "$dir" atom_build_log="$dir/build.log" >"$dir/log.txt" 2>&1 && fail "a matching log must fail the atom"
[ "$(status "$dir")" = "failure" ] || fail "status: $(cat "$dir/output.json")"
[ "$(output "$dir" matches)" -ge 1 ] || fail "match count: $(cat "$dir/output.json")"

# 2. the same log with failWhenMatch=false is a report, not a verdict
dir="$(mktemp -d)"
printf 'INFO all good\n' >"$dir/build.log"
printf '{"pattern": "ERROR", "useBuildLog": "true", "failWhenMatch": "false"}' >"$dir/input.json"
run "$dir" atom_build_log="$dir/build.log" >/dev/null 2>&1 || fail "no match must not fail"
[ "$(output "$dir" matches)" = "0" ] || fail "expected zero matches: $(cat "$dir/output.json")"

# 3. failWhenMissing: the required line is absent
dir="$(mktemp -d)"
printf 'still running\n' >"$dir/build.log"
printf '{"pattern": "BUILD SUCCESS", "useBuildLog": "true", "failWhenMatch": "false", "failWhenMissing": "true"}' >"$dir/input.json"
run "$dir" atom_build_log="$dir/build.log" >/dev/null 2>&1 && fail "a missing required line must fail"
grep -q "no line matched" "$dir/output.json" || fail "message not readable: $(cat "$dir/output.json")"

# 4. workspace files are searched too
dir="$(mktemp -d)"; mkdir -p "$dir/logs"
printf 'ok\nDEPRECATED: old api\n' >"$dir/logs/app.txt"
printf '{"pattern": "DEPRECATED", "useBuildLog": "false", "files": "logs/*.txt", "failWhenMatch": "false"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "file scan failed"
[ "$(output "$dir" matches)" = "1" ] || fail "file matches: $(cat "$dir/output.json")"

# 5. an invalid regex is a user error, not a crash
dir="$(mktemp -d)"
printf '{"pattern": "([a-z", "useBuildLog": "false"}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a broken regex must fail"
grep -q "not a valid regular expression" "$dir/output.json" || fail "message not readable"

echo "smoke ok: match->fail, no match, failWhenMissing, file scan, bad regex"
