import json
import os
import sys

import config

ALL_TOOLS = {
    "collectDatabaseMetrics", "sendAlertEmail",
    "getBlockingChains", "getReplicationStatus", "listActiveSessions",
    "getWaitEventDistribution", "getTopSlowQueries", "resetSlowQueryStats",
    "getSqlExecutionPlan", "getVacuumAndBloatStatus", "getTableAccessStats",
    "getWeather",
}
WRITE_TOOLS = {"sendAlertEmail", "resetSlowQueryStats"}

REQUIRED_FIELDS = [
    "case_id", "category", "difficulty", "query", "expected_intent",
    "expected_tools", "optional_tools", "forbidden_tools",
    "min_tool_calls", "max_tool_calls", "expected_tool_edges",
    "expected_params", "must_ask", "expect_no_match", "source_case_id",
]

VALID_CATEGORIES = {"single_tool", "multi_tool", "chain", "param_boundary",
                    "write_boundary", "no_match"}
VALID_DIFFICULTIES = {"EASY", "MEDIUM", "HARD"}
VALID_INTENTS = {"COMPLEX_TASK", "KNOWLEDGE_BASE"}
VALID_OUTCOMES = {"SUCCESS", "FAILURE", "ANY"}
SENTINELS = {"__ANY__", "__ANY_OR_OMIT__", "__ASK__", "__PRESENT__",
             "__FROM_TOOL_OUTPUT__"}


def fail(errors, message):
    errors.append(message)


def load_cases(path):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for index, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise SystemExit("line %d is not valid JSON: %s" % (index, exc))
    return rows


def check_case(row, errors):
    cid = row.get("case_id", "<no id>")
    for field in REQUIRED_FIELDS:
        if field not in row:
            fail(errors, "%s missing field %s" % (cid, field))
    if row.get("category") not in VALID_CATEGORIES:
        fail(errors, "%s bad category %r" % (cid, row.get("category")))
    if row.get("difficulty") not in VALID_DIFFICULTIES:
        fail(errors, "%s bad difficulty %r" % (cid, row.get("difficulty")))
    if row.get("expected_intent") not in VALID_INTENTS:
        fail(errors, "%s bad expected_intent %r" % (cid, row.get("expected_intent")))
    if row.get("tool_outcome_expected") not in VALID_OUTCOMES:
        fail(errors, "%s bad tool_outcome_expected %r"
             % (cid, row.get("tool_outcome_expected")))
    if row.get("source_case_id") != cid:
        fail(errors, "%s source_case_id mismatch %r" % (cid, row.get("source_case_id")))

    expected = row.get("expected_tools", [])
    optional = row.get("optional_tools", [])
    forbidden = row.get("forbidden_tools", [])
    for tool in expected + optional + forbidden:
        if tool not in ALL_TOOLS:
            fail(errors, "%s unknown tool %r" % (cid, tool))
    if set(expected) & set(forbidden):
        fail(errors, "%s tool both expected and forbidden" % cid)
    if set(optional) & set(forbidden):
        fail(errors, "%s tool both optional and forbidden" % cid)

    params = row.get("expected_params", {})
    for tool in params:
        if tool not in ALL_TOOLS:
            fail(errors, "%s expected_params for unknown tool %r" % (cid, tool))
        if tool not in expected and tool not in optional:
            fail(errors, "%s expected_params for %s which is neither expected nor optional"
                 % (cid, tool))
        spec = params[tool]
        for key in ("required", "optional", "not_evaluated", "any_of"):
            if key not in spec:
                fail(errors, "%s %s missing params key %s" % (cid, tool, key))
        for value in list(spec.get("required", {}).values()) + list(spec.get("optional", {}).values()):
            if isinstance(value, dict):
                if "__ENUM_EXPECTED__" not in value:
                    fail(errors, "%s %s bad param dict %r" % (cid, tool, value))
            elif not isinstance(value, str):
                fail(errors, "%s %s param value must be string or enum dict, got %r"
                     % (cid, tool, value))

    mincalls = row.get("min_tool_calls")
    maxcalls = row.get("max_tool_calls")
    if not isinstance(mincalls, int) or mincalls < 0:
        fail(errors, "%s bad min_tool_calls %r" % (cid, mincalls))
    if maxcalls is not None and maxcalls < mincalls:
        fail(errors, "%s max_tool_calls < min_tool_calls" % cid)

    for edge in row.get("expected_tool_edges", []):
        if len(edge) != 2:
            fail(errors, "%s bad edge %r" % (cid, edge))
            continue
        if edge[0] not in expected + optional or edge[1] not in expected + optional:
            fail(errors, "%s edge references tool outside expected/optional: %r" % (cid, edge))

    if row.get("expect_no_match"):
        if expected:
            fail(errors, "%s expect_no_match but expected_tools non-empty" % cid)
        if mincalls != 0:
            fail(errors, "%s expect_no_match but min_tool_calls != 0" % cid)
        if not forbidden:
            fail(errors, "%s expect_no_match but forbidden_tools empty" % cid)

    if row.get("category") == "write_boundary":
        for tool in ("sendAlertEmail", "resetSlowQueryStats"):
            if tool not in forbidden:
                fail(errors, "%s write_boundary must forbid %s" % (cid, tool))
        if expected:
            fail(errors, "%s write_boundary has expected_tools" % cid)


def main():
    if not os.path.exists(config.TOOL_DATASET):
        raise SystemExit("dataset not found: %s" % config.TOOL_DATASET)

    rows = load_cases(config.TOOL_DATASET)
    errors = []

    if len(rows) != 72:
        fail(errors, "expected 72 cases, found %d" % len(rows))

    seen = set()
    for row in rows:
        cid = row.get("case_id")
        if cid in seen:
            fail(errors, "duplicate case_id %s" % cid)
        seen.add(cid)
        check_case(row, errors)

    counts = {}
    for row in rows:
        counts[row["category"]] = counts.get(row["category"], 0) + 1

    print("cases: %d" % len(rows))
    for name in sorted(counts):
        print("  %-16s %d" % (name, counts[name]))

    if errors:
        print("")
        print("FAILED with %d problem(s):" % len(errors))
        for message in errors:
            print("  - %s" % message)
        sys.exit(1)

    print("")
    print("OK")


if __name__ == "__main__":
    main()
