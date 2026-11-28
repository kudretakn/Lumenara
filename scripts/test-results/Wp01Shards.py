"""Deterministic, whole-class partitions and fail-closed cross-device coverage."""

ALGORITHM = "balanced-classes-v1"


def partition_tests(tests, count):
    if count < 1:
        raise ValueError("Shard count must be positive")
    classes = {}
    for test in sorted(tests):
        cls, sep, method = test.partition("#")
        if not cls or not sep or not method:
            raise ValueError("Invalid inventory test ID: " + test)
        classes.setdefault(cls, []).append(test)
    shards = [[] for _ in range(count)]
    # Keep classes together, balance by test count, break every tie explicitly.
    for cls in sorted(classes, key=lambda name: (-len(classes[name]), name)):
        index = min(range(count), key=lambda i: (len(shards[i]), i))
        shards[index].extend(classes[cls])
    return [sorted(shard) for shard in shards]


def verify_shards(reports, count):
    if len(reports) != count or count < 1:
        raise ValueError("Missing or extra shard reports")
    full, seen, indices = None, set(), set()
    for report in reports:
        if not isinstance(report, dict) or report.get("schema_version") != 1:
            raise ValueError("Invalid shard report schema")
        shard = report.get("shard", {})
        index = shard.get("index")
        if (shard.get("count") != count or type(index) is not int or not 0 <= index < count
                or index in indices or shard.get("algorithm") != ALGORITHM):
            raise ValueError("Invalid or duplicate shard metadata")
        indices.add(index)
        discovery = report.get("full_inventory", {})
        tests = discovery.get("results", {})
        if not tests or discovery.get("errors") or any(status != "PASS" for status in tests.values()):
            raise ValueError("Invalid full-suite discovery")
        if full is None:
            full = set(tests)
        if set(tests) != full:
            raise ValueError("Full inventories differ between shards")
        expected = set(partition_tests(full, count)[index])
        inventory = report.get("inventory", {})
        if (not expected or set(inventory.get("results", {})) != expected or inventory.get("errors")
                or set(report.get("results", {})) != expected):
            raise ValueError("Shard inventory/results differ from deterministic partition")
        if report.get("success") is not True or report.get("errors"):
            raise ValueError("Shard gate did not pass")
        if any(status not in ("PASS", "RETRIED") and not (
                status == "SKIP" and report.get("allowed_skips", {}).get(test))
               for test, status in report["results"].items()):
            raise ValueError("Shard has failing or unreviewed results")
        if seen & expected:
            raise ValueError("Overlapping shard inventories")
        seen.update(expected)
    if seen != full:
        raise ValueError("Shard inventory union differs from full suite")
    return len(full)
