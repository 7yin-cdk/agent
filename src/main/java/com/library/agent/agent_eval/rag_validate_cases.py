import json
import sys

import config

REQUIRED_FIELDS = (
    "case_id",
    "category",
    "difficulty",
    "query",
    "qrels",
    "seed_chunk_id",
    "source_file",
    "section_depth",
    "keyword_friendly",
    "reviewed",
)

TYPES = ("concept", "param", "troubleshoot", "howto", "compare")
DIFFICULTIES = ("EASY", "MEDIUM", "HARD")

MIN_CASES = 120
MIN_FILES = 25
MIN_PER_DEPTH = 10
MIN_PER_TYPE = 20
MAX_PER_SEED = 3

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def load_cases(path):
    cases = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    return cases


def load_chunk_ids(path):
    ids = set()
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                ids.add(json.loads(line).get("chunk_id"))
    return ids


def count_by(cases, key):
    result = {}
    for case in cases:
        value = case.get(key)
        result[value] = result.get(value, 0) + 1
    return result


def report(label, ok, detail):
    print(f"{'PASS' if ok else 'FAIL'}  {label}  {detail}")
    return ok


def main():
    cases = load_cases(config.RAG_DATASET)
    snapshot_ids = load_chunk_ids(config.KB_SNAPSHOT)
    results = []

    results.append(report("用例数 >= %d" % MIN_CASES, len(cases) >= MIN_CASES, f"实际 {len(cases)}"))

    missing_fields = [
        (case.get("case_id"), field)
        for case in cases
        for field in REQUIRED_FIELDS
        if field not in case
    ]
    results.append(report("必填字段齐全", not missing_fields, f"缺失 {len(missing_fields)} 处"))

    unreviewed = [case.get("case_id") for case in cases if case.get("reviewed") is not True]
    results.append(report("全部已复核", not unreviewed, f"未复核 {len(unreviewed)} 条"))

    distinct_files = {case.get("source_file") for case in cases}
    results.append(
        report("文档覆盖 >= %d" % MIN_FILES, len(distinct_files) >= MIN_FILES, f"实际 {len(distinct_files)}")
    )

    by_type = count_by(cases, "category")
    bad_types = [key for key in by_type if key not in TYPES]
    type_detail = " ".join(f"{key}={by_type.get(key, 0)}" for key in TYPES)
    results.append(
        report("查询类型覆盖", not bad_types and all(by_type.get(key, 0) >= MIN_PER_TYPE for key in TYPES), type_detail)
    )

    by_depth = count_by(cases, "section_depth")
    depth_groups = {
        "1": sum(count for depth, count in by_depth.items() if depth == 1),
        "2": sum(count for depth, count in by_depth.items() if depth == 2),
        "3": sum(count for depth, count in by_depth.items() if depth == 3),
        ">=4": sum(count for depth, count in by_depth.items() if (depth or 0) >= 4),
    }
    depth_detail = " ".join(f"{key}={value}" for key, value in depth_groups.items())
    results.append(
        report("层级覆盖各 >= %d" % MIN_PER_DEPTH, all(v >= MIN_PER_DEPTH for v in depth_groups.values()), depth_detail)
    )

    by_difficulty = count_by(cases, "difficulty")
    bad_difficulty = [key for key in by_difficulty if key not in DIFFICULTIES]
    diff_detail = " ".join(f"{key}={by_difficulty.get(key, 0)}" for key in DIFFICULTIES)
    results.append(report("难度分布", not bad_difficulty, diff_detail))

    seed_counts = count_by(cases, "seed_chunk_id")
    hot_seeds = {key: value for key, value in seed_counts.items() if value > MAX_PER_SEED}
    results.append(
        report("单切片 <= %d 条" % MAX_PER_SEED, not hot_seeds, f"超限 {len(hot_seeds)} 个切片")
    )

    unknown_seeds = [seed for seed in seed_counts if seed not in snapshot_ids]
    results.append(report("种子切片都在快照中", not unknown_seeds, f"缺失 {len(unknown_seeds)} 个"))

    duplicate_ids = [case_id for case_id, count in count_by(cases, "case_id").items() if count > 1]
    results.append(report("case_id 唯一", not duplicate_ids, f"重复 {len(duplicate_ids)} 个"))

    print()
    if all(results):
        print("校验通过")
    else:
        print("校验未通过，请修正上述 FAIL 项")
        sys.exit(1)


if __name__ == "__main__":
    main()
