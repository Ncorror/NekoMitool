#!/usr/bin/env python3
"""Lightweight static checks for NekoFlash.

This script intentionally avoids Android SDK/Gradle so it can run in CI before
network-dependent dependency resolution and in restricted environments.
"""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ERRORS: list[str] = []
WARNINGS: list[str] = []


def fail(message: str) -> None:
    ERRORS.append(message)


def warn(message: str) -> None:
    WARNINGS.append(message)


def parse_xml(path: Path) -> ET.ElementTree:
    try:
        return ET.parse(path)
    except Exception as exc:  # noqa: BLE001 - diagnostic script
        fail(f"XML parse failed: {path.relative_to(ROOT)}: {exc}")
        raise


def resource_names(path: Path) -> set[str]:
    if not path.exists():
        fail(f"Missing resource file: {path.relative_to(ROOT)}")
        return set()
    tree = parse_xml(path)
    names: set[str] = set()
    for node in tree.getroot().iter():
        if node.tag == "string" and "name" in node.attrib:
            names.add(node.attrib["name"])
    return names


def check_xml_files() -> None:
    for path in sorted((ROOT / "app/src/main/res").rglob("*.xml")):
        parse_xml(path)


def check_string_parity() -> None:
    base = resource_names(ROOT / "app/src/main/res/values/strings.xml")
    ru = resource_names(ROOT / "app/src/main/res/values-ru/strings.xml")
    missing_ru = sorted(base - ru)
    missing_base = sorted(ru - base)
    if missing_ru:
        fail("Missing in values-ru/strings.xml: " + ", ".join(missing_ru))
    if missing_base:
        fail("Missing in values/strings.xml: " + ", ".join(missing_base))
    if base and ru:
        print(f"strings parity: OK ({len(base)} keys)")


def check_kotlin_char_literals() -> None:
    """
    Ловит повреждённые символьные литералы вида  append('  с переносом строки
    внутри кавычек (escape \\n потерял слеш при копировании/распаковке).
    Такое не видно глазом, но валит компиляцию 'Incorrect character literal'.
    """
    import re, os
    bad = []
    src_dir = ROOT / "app/src/main/java"
    for root, _, files in os.walk(src_dir):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(root, fn)
            lines = open(path, encoding="utf-8").read().split("\n")
            for i, line in enumerate(lines, 1):
                # строка заканчивается одиночной кавычкой-литералом без закрытия
                if re.search(r"\('$", line.rstrip()) or re.search(r"=\s*'$", line.rstrip()):
                    bad.append(f"{os.path.relpath(path, ROOT)}:{i}")
    if bad:
        fail("Broken char literals (unclosed '): " + ", ".join(bad))
    else:
        print("kotlin char literals: OK")


def check_string_values_aapt_safe() -> None:
    """
    Значение <string>, начинающееся с '?' или '@', AAPT трактует как ссылку
    на ресурс/атрибут (?attr/..., @id/...) и падает с 'resource not found'.
    Такие значения должны быть экранированы '\\?' / '\\@'.
    """
    import re
    bad = []
    for rel in ("app/src/main/res/values/strings.xml", "app/src/main/res/values-ru/strings.xml"):
        path = ROOT / rel
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        # значение строки: <string name="x">VALUE</string>
        for m in re.finditer(r'<string name="(\w+)"[^>]*>(.*?)</string>', text, re.DOTALL):
            name, value = m.group(1), m.group(2)
            if value[:1] in ("?", "@"):
                bad.append(f"{rel}: {name} starts with '{value[:1]}' (escape as '\\{value[:1]}')")
            # Неэкранированный апостроф ломает AAPT (нужно \' или строка в "...").
            unescaped = re.sub(r"\\'", "", value)
            if "'" in unescaped and not (value.startswith('"') and value.endswith('"')):
                bad.append(f"{rel}: {name} has an unescaped apostrophe (use \\' )")
    if bad:
        fail("AAPT-unsafe string values:\n  " + "\n  ".join(bad))
    else:
        print("string values AAPT-safe: OK")


def check_versions() -> None:
    build = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    code = re.search(r"versionCode\s+(\d+)", build)
    name = re.search(r"versionName\s+\"([^\"]+)\"", build)
    if not code or not name:
        fail("Could not parse versionCode/versionName from app/build.gradle")
        return
    print(f"version: code={code.group(1)}, name={name.group(1)}")
    if int(code.group(1)) < 31:
        fail("versionCode must be >= 31 for NekoFlash rebrand")
    version_name = name.group(1)
    if not re.fullmatch(r"2\.\d+\.\d+-nekomiflash", version_name):
        fail("versionName must match 2.x.y-nekomiflash")


def check_gradle_wrapper() -> None:
    wrapper_props = ROOT / "gradle/wrapper/gradle-wrapper.properties"
    wrapper_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    gradlew = ROOT / "gradlew"
    if not wrapper_props.exists():
        fail("Missing gradle/wrapper/gradle-wrapper.properties")
    else:
        text = wrapper_props.read_text(encoding="utf-8")
        if "gradle-8.4-bin.zip" not in text:
            fail("gradle-wrapper.properties must point to Gradle 8.4 bin distribution")
    if not gradlew.exists():
        fail("Missing gradlew")
    else:
        text = gradlew.read_text(encoding="utf-8", errors="ignore")
        if "GradleWrapperMain" not in text and "using Gradle from PATH" not in text:
            fail("gradlew must call GradleWrapperMain or provide a PATH fallback")
    if not wrapper_jar.exists():
        warn("gradle-wrapper.jar is absent; CI/local build will use Gradle from PATH until the wrapper workflow is run")
    else:
        try:
            with zipfile.ZipFile(wrapper_jar) as jar:
                if "org/gradle/wrapper/GradleWrapperMain.class" not in jar.namelist():
                    fail("gradle-wrapper.jar does not contain GradleWrapperMain")
        except zipfile.BadZipFile:
            fail("gradle-wrapper.jar is not a valid jar/zip")


def check_workflows() -> None:
    build_yml = ROOT / ".github/workflows/build.yml"
    add_wrapper_yml = ROOT / ".github/workflows/add-wrapper.yml"
    for path in (build_yml, add_wrapper_yml):
        if not path.exists():
            fail(f"Missing workflow: {path.relative_to(ROOT)}")
    if build_yml.exists():
        text = build_yml.read_text(encoding="utf-8")
        for token in ("gradle/actions/setup-gradle", "scripts/check_project.py", "assembleDebug", "forum-build/*.apk"):
            if token not in text:
                fail(f"build.yml does not contain required token: {token}")
    if add_wrapper_yml.exists():
        text = add_wrapper_yml.read_text(encoding="utf-8")
        for token in ("gradle wrapper --gradle-version 8.4", "gradle-wrapper.jar", "contents: write"):
            if token not in text:
                fail(f"add-wrapper.yml does not contain required token: {token}")



def check_layout_strings_are_resources() -> None:
    layout_dir = ROOT / "app/src/main/res/layout"
    pattern = re.compile(r"android:(text|hint)=\"([^\"]*)\"")
    for path in sorted(layout_dir.glob("*.xml")):
        text = path.read_text(encoding="utf-8")
        for attr, value in pattern.findall(text):
            if value and not value.startswith("@"):
                fail(f"Hardcoded android:{attr} in {path.relative_to(ROOT)}: {value}")


def check_fileprovider_scope() -> None:
    path = ROOT / "app/src/main/res/xml/file_paths.xml"
    if not path.exists():
        fail("Missing app/src/main/res/xml/file_paths.xml")
        return
    text = path.read_text(encoding="utf-8")
    if 'path="."' in text:
        fail("FileProvider must not expose the whole external storage with path=\".\"")
    for token in ('path="Download/NekoMiFlash/logs/"', 'path="Download/NekoMiFlash/reports/"'):
        if token not in text:
            fail(f"FileProvider missing scoped entry: {token}")


def check_self_test_hooks() -> None:
    vm = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    adb = ROOT / "app/src/main/java/ru/forum/adbfastboottool/AdbProtocol.kt"
    fastboot = ROOT / "app/src/main/java/ru/forum/adbfastboottool/FastbootProtocol.kt"
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    layout = ROOT / "app/src/main/res/layout/activity_main.xml"
    checks = {
        vm: ["fun runSelfTest()", "=== SELF-TEST / SMOKE TEST ==="],
        adb: ["fun runSelfTest(): Boolean", "shell_v2 exit-code probe", "sync STAT /sdcard"],
        fastboot: ["fun runSelfTest(): Boolean", "FASTBOOT SELF-TEST", "Guard check"],
        main: ["TerminalAction.SelfTest", "smoke-test", "doctor"],
        layout: ["btnSelfTest", "layout_self_test"],
    }
    for path, tokens in checks.items():
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                fail(f"Self-test hook missing in {path.relative_to(ROOT)}: {token}")


def check_self_test_ui() -> None:
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    vm = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    layout = ROOT / "app/src/main/res/layout/activity_main.xml"
    checks = {
        main: ["renderSelfTestStatus", "openReportsFolder", "DocumentsContract.EXTRA_INITIAL_URI", "isOpenReportsCommand"],
        vm: ["SelfTestStatus", "SelfTestResult", "selfTestStatus", "reportsDirectory"],
        layout: ["tvSelfTestStatus", "btnOpenReportsFolder"],
    }
    for path, tokens in checks.items():
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                fail(f"Self-test UI hook missing in {path.relative_to(ROOT)}: {token}")


def check_private_reports() -> None:
    sanitizer = ROOT / "app/src/main/java/ru/forum/adbfastboottool/ReportSanitizer.kt"
    forum = ROOT / "app/src/main/java/ru/forum/adbfastboottool/ForumReportManager.kt"
    vm = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    for path in (sanitizer, forum, vm):
        if not path.exists():
            fail(f"Missing private report file: {path.relative_to(ROOT)}")
            continue
    if sanitizer.exists():
        text = sanitizer.read_text(encoding="utf-8")
        for token in ("REDACTED_SERIAL", "sanitizeText", "sanitizeDeviceStoragePaths", "sanitizeLongHexIdentifiers"):
            if token not in text:
                fail(f"ReportSanitizer missing token: {token}")
    if forum.exists():
        text = forum.read_text(encoding="utf-8")
        for token in ("PrivacyMode.SANITIZED", "forum-report.v3", "ReportSanitizer.sanitizeText", "privacyMode"):
            if token not in text:
                fail(f"ForumReportManager privacy hook missing: {token}")
    if vm.exists():
        text = vm.read_text(encoding="utf-8")
        for token in ("selftest.v2", "Privacy mode: sanitized", "ReportSanitizer.sanitizeLines"):
            if token not in text:
                fail(f"DeviceViewModel self-test privacy hook missing: {token}")


def check_xiaomi_fastboot_rom_hooks() -> None:
    manager = ROOT / "app/src/main/java/ru/forum/adbfastboottool/XiaomiFastbootRomManager.kt"
    vm = ROOT / "app/src/main/java/ru/forum/adbfastboottool/DeviceViewModel.kt"
    fastboot = ROOT / "app/src/main/java/ru/forum/adbfastboottool/FastbootProtocol.kt"
    main = ROOT / "app/src/main/java/ru/forum/adbfastboottool/MainActivity.kt"
    layout = ROOT / "app/src/main/res/layout/activity_main.xml"
    for path in (manager, vm, fastboot, main, layout):
        if not path.exists():
            fail(f"Missing Xiaomi Fastboot ROM file: {path.relative_to(ROOT)}")
            continue
    checks = {
        manager: ["object XiaomiFastbootRomManager", "FlashMode", "UpdateSuper", "%CURRENT_DIR%", "--disable-verity", "update-super detected", "extractAntiRollbackIndexes", "CRITICAL_FIRMWARE_PARTITIONS", "DataImpact", "WipeData", "DATA IMPACT SUMMARY", "Sparse/split chunks", "splitSequenceWarnings", "sourceFingerprint", "StoragePreflight", "STORAGE / TRANSFER PREFLIGHT", "buildStoragePreflight", "resetExtractionDirectory", ".partial"],
        vm: ["analyzeXiaomiFastbootRom", "runXiaomiFastbootRom", "writeXiaomiRomAnalysisReport", "xiaomi-rom-analysis-", "PendingXiaomiRomFlash", "requestFastbootdAndResume", "FASTBOOTD_RESUME_TIMEOUT_MS", "executeUpdateSuperAction", "update-super:", "xiaomi-rom-flash-session-", "DRY-RUN PLAN", "xiaomiAntiRollbackBlockReason", "executeXiaomiWipeDataAction", "dataImpactText", "formatDurationShort", 'append(" transfer=")'],
        fastboot: ["antiRollback", 'getVar("anti")', "Anti-rollback index", "transferDownloadPayload", "formatBytesPerSecond", "explainFastbootFailure"],
        main: ["btnXiaomiRomAnalyze", "btnXiaomiRomFlashClean", "btnXiaomiRomFlashSaveData"],
        layout: ["layout_xiaomi_rom_title", "btnXiaomiRomAnalyze", "btnXiaomiRomFlashClean", "btnXiaomiRomFlashSaveData"],
    }
    for path, tokens in checks.items():
        text = path.read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                fail(f"Xiaomi Fastboot ROM hook missing in {path.relative_to(ROOT)}: {token}")

def main() -> int:
    check_xml_files()
    check_string_parity()
    check_string_values_aapt_safe()
    check_kotlin_char_literals()
    check_versions()
    check_gradle_wrapper()
    check_workflows()
    check_fileprovider_scope()
    check_layout_strings_are_resources()
    check_self_test_hooks()
    check_self_test_ui()
    check_private_reports()
    check_xiaomi_fastboot_rom_hooks()

    for message in WARNINGS:
        print(f"WARNING: {message}")
    if ERRORS:
        print("\nFAILED:")
        for message in ERRORS:
            print(f" - {message}")
        return 1
    print("static project check: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
