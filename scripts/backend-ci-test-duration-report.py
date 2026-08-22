import argparse
import glob
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def collect_class_durations(report_paths):
    durations, warnings = collect_class_durations_with_warnings(report_paths)
    if warnings:
        raise ValueError("; ".join(warnings))
    return durations


def collect_class_durations_with_warnings(report_paths):
    totals = defaultdict(lambda: [0.0, 0])
    warnings = []

    if not report_paths:
        return [], ["no JUnit XML reports found"]

    for report_path in report_paths:
        try:
            root = ET.parse(report_path).getroot()
        except (ET.ParseError, OSError) as error:
            warnings.append(f"could not parse {report_path}: {error}")
            continue

        for testcase in root.iter("testcase"):
            class_name = testcase.get("classname", "<unknown>")
            try:
                duration = float(testcase.get("time", "0"))
            except ValueError:
                warnings.append(
                    f"could not parse testcase duration in {report_path}: {class_name}"
                )
                continue
            totals[class_name][0] += duration
            totals[class_name][1] += 1

    durations = [
        (class_name, total_duration, test_count)
        for class_name, (total_duration, test_count) in totals.items()
    ]
    return sorted(durations, key=lambda item: (-item[1], item[0])), warnings


def render_markdown(shard, durations, parsed_file_count):
    lines = [
        f"## Integration test duration: shard {shard}",
        "",
        f"Parsed JUnit XML files: {parsed_file_count}",
        "",
        "| Rank | Test class | Total seconds | Test cases |",
        "| ---: | --- | ---: | ---: |",
    ]
    for rank, (class_name, duration, test_count) in enumerate(durations, start=1):
        lines.append(f"| {rank} | `{class_name}` | {duration:.3f} | {test_count} |")
    if not durations:
        lines.append("| - | No parsed test cases | 0.000 | 0 |")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--shard", required=True)
    parser.add_argument("--report-glob", required=True)
    parser.add_argument("--summary-file", required=True)
    parser.add_argument("--output-file", required=True)
    parser.add_argument("--top", type=int, default=25)
    arguments = parser.parse_args()

    report_paths = [Path(path) for path in glob.glob(arguments.report_glob)]
    durations, warnings = collect_class_durations_with_warnings(report_paths)
    markdown = render_markdown(arguments.shard, durations[: arguments.top], len(report_paths))
    if warnings:
        markdown += "\n### Warnings\n\n" + "\n".join(f"- {warning}" for warning in warnings) + "\n"

    Path(arguments.output_file).write_text(markdown, encoding="utf-8")
    with Path(arguments.summary_file).open("a", encoding="utf-8") as summary_file:
        summary_file.write(markdown)
    print(markdown, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
