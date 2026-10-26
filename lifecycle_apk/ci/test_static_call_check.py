#!/usr/bin/env python3
"""Static-call guard regressions: generic companion functions still resolve."""
from contextlib import redirect_stdout
import importlib.util
from io import StringIO
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "static_call_check", Path(__file__).with_name("static_call_check.py")
)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class StaticCallCheckTest(unittest.TestCase):
    def run_guard(self, declaration, call, caller_package="com.lifecyclebot.engine", imports=""):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / guard.SRC / "com/lifecyclebot/engine"
            source.mkdir(parents=True)
            (source / "SubmissionReceipt.kt").write_text(
                "package com.lifecyclebot.engine\n" + declaration, encoding="utf-8"
            )
            caller = source / "Caller.kt"
            caller.write_text(
                f"package {caller_package}\n{imports}\nfun consume() {{ {call} }}\n",
                encoding="utf-8",
            )
            output = StringIO()
            with patch.object(guard, "REPO", str(root)), \
                    patch.object(guard, "changed_files", return_value=[str(caller.relative_to(root))]), \
                    patch.object(sys, "argv", ["static_call_check.py", "HEAD"]), \
                    redirect_stdout(output):
                result = guard.main()
            return result, output.getvalue()

    def test_nullable_generic_companion_function_is_a_valid_static_receiver(self):
        result, output = self.run_guard(
            """class SubmissionReceipt {
    companion object {
        fun <T : String?> observe(send: () -> T): T = send()
    }
}
""",
            'SubmissionReceipt.observe({ "signature" })',
        )
        self.assertEqual(0, result, output)
        self.assertIn("1 changed Kotlin file(s) scanned", output)

    def test_companion_function_with_nested_generic_bound_is_valid(self):
        result, output = self.run_guard(
            """class SubmissionReceipt {
    companion object {
        fun <T : Comparable<T>> observe(value: T): T = value
    }
}
""",
            "SubmissionReceipt.observe(1)",
        )
        self.assertEqual(0, result, output)

    def test_ordinary_companion_function_remains_valid(self):
        result, output = self.run_guard(
            """class SubmissionReceipt {
    companion object {
        fun observe(value: String): String = value
    }
}
""",
            'SubmissionReceipt.observe("signature")',
        )
        self.assertEqual(0, result, output)

    def test_generic_instance_function_remains_invalid_when_called_statically(self):
        declaration = """class SubmissionReceipt {
    companion object {
        fun <T : String?> create(send: () -> T): T = send()
    }
    fun <T : String?> observe(send: () -> T): T = send()
}
"""
        callers = [
            ("com.lifecyclebot.engine", "", 'SubmissionReceipt.observe({ "signature" })'),
            ("com.lifecyclebot.other", "import com.lifecyclebot.engine.SubmissionReceipt",
             'SubmissionReceipt.observe({ "signature" })'),
            ("com.lifecyclebot.other", "",
             'com.lifecyclebot.engine.SubmissionReceipt.observe({ "signature" })'),
        ]
        for package, imports, call in callers:
            with self.subTest(package=package, imports=imports, call=call):
                result, output = self.run_guard(declaration, call, package, imports)
                self.assertEqual(1, result, output)
                self.assertIn("SubmissionReceipt.observe()", output)


if __name__ == "__main__":
    unittest.main()
