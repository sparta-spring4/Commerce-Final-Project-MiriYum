import importlib.util
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPORTER_PATH = ROOT / "scripts" / "backend-ci-test-duration-report.py"


def load_reporter_module():
    spec = importlib.util.spec_from_file_location("backend_ci_test_duration_report", REPORTER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BackendCiTestDurationReportTest(unittest.TestCase):
    def test_sums_testcase_duration_by_class_and_orders_slowest_first(self):
        with temporary_junit_report(
                """
                <testsuite>
                  <testcase classname="com.miriyum.FastTest" name="first" time="0.2" />
                  <testcase classname="com.miriyum.SlowTest" name="first" time="2.5" />
                  <testcase classname="com.miriyum.SlowTest" name="second" time="1.5" />
                </testsuite>
                """
        ) as report:
            reporter = load_reporter_module()

            durations = reporter.collect_class_durations([report])

        self.assertEqual([
            ("com.miriyum.SlowTest", 4.0, 2),
            ("com.miriyum.FastTest", 0.2, 1),
        ], durations)

    def test_writes_markdown_summary_with_shard_and_top_classes(self):
        reporter = load_reporter_module()

        summary = reporter.render_markdown(
            shard="b",
            durations=[("com.miriyum.SlowTest", 4.0, 2)],
            parsed_file_count=1,
        )

        self.assertIn("## Integration test duration: shard b", summary)
        self.assertIn("com.miriyum.SlowTest", summary)
        self.assertIn("4.000", summary)
        self.assertIn("2", summary)

    def test_skips_malformed_xml_and_returns_warning(self):
        with temporary_junit_report("<testsuite>") as malformed_report:
            reporter = load_reporter_module()

            durations, warnings = reporter.collect_class_durations_with_warnings([malformed_report])

        self.assertEqual([], durations)
        self.assertEqual(1, len(warnings))
        self.assertIn("could not parse", warnings[0])


class temporary_junit_report:
    def __init__(self, content):
        self.content = content
        self.temporary_directory = None
        self.path = None

    def __enter__(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary_directory.name) / "TEST-example.xml"
        self.path.write_text(self.content, encoding="utf-8")
        return self.path

    def __exit__(self, exc_type, exc_value, traceback):
        self.temporary_directory.cleanup()


if __name__ == "__main__":
    unittest.main()
