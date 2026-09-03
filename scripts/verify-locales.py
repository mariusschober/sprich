#!/usr/bin/env python3
"""Fail closed when a production interface locale is incomplete or unsafe."""

import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
LOCALES = ("en", "de", "fr", "es")
FORMAT = re.compile(r"%(?:\d+\$)?(?:[-+# 0,(]*\d*(?:\.\d+)?)?[a-zA-Z%]")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit("FAIL: " + message)


def strings_for(locale: str) -> dict[str, str]:
    folder = "values" if locale == "en" else f"values-{locale}"
    path = RES / folder / "strings.xml"
    require(path.is_file(), f"missing {locale} string resources")
    entries = ET.parse(path).getroot().findall("string")
    names = [entry.get("name") for entry in entries]
    require(None not in names and len(names) == len(set(names)), f"duplicate or unnamed {locale} string")
    return {entry.get("name"): "".join(entry.itertext()) for entry in entries}


catalog = {locale: strings_for(locale) for locale in LOCALES}
english_keys = set(catalog["en"])
require(english_keys, "English resource set is empty")
for locale in LOCALES[1:]:
    keys = set(catalog[locale])
    require(keys == english_keys,
            f"{locale} keys differ; missing={sorted(english_keys - keys)}, extra={sorted(keys - english_keys)}")
    for key in sorted(english_keys):
        require(FORMAT.findall(catalog[locale][key]) == FORMAT.findall(catalog["en"][key]),
                f"format placeholders differ for {locale}:{key}")

locale_xml = ET.parse(RES / "xml/locales_config.xml").getroot()
declared = [entry.get(ANDROID_NS + "name") for entry in locale_xml.findall("locale")]
require(tuple(declared) == LOCALES, f"locale metadata must be exactly {LOCALES}, found {tuple(declared)}")

manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
application = manifest.find("application")
require(application is not None and application.get(ANDROID_NS + "localeConfig") == "@xml/locales_config",
        "application does not expose localeConfig")

build_script = (ROOT / "app/build.gradle.kts").read_text()
require(re.search(r"bundle\s*\{[\s\S]*?language\s*\{[\s\S]*?enableSplit\s*=\s*false", build_script),
        "app bundle language splitting must stay disabled so every install contains all four locales")

entries = json.loads((ROOT / "app/src/main/assets/notices/index.json").read_text())
notice_ids = {entry["id"] for entry in entries}
notice_source = (ROOT / "app/src/main/java/com/sprich/app/ui/settings/NoticesScreen.kt").read_text()
mapped_ids = set(re.findall(r'^\s*"([^"]+)"\s+to\s+R\.string\.notice_description_', notice_source, re.MULTILINE))
require(mapped_ids == notice_ids,
        f"localized notice descriptions differ; missing={sorted(notice_ids - mapped_ids)}, extra={sorted(mapped_ids - notice_ids)}")

hard_coded = []
ui_literal_patterns = (
    r"\bText\s*\(\s*\"",
    r"\bToast\.makeText\s*\([^,]+,\s*\"",
    r"\b(?:contentDescription\s*=|setText\s*\(|announceForAccessibility\s*\()\s*\"",
)
for source in (ROOT / "app/src/main/java").rglob("*.kt"):
    for line_number, line in enumerate(source.read_text().splitlines(), 1):
        if any(re.search(pattern, line) for pattern in ui_literal_patterns):
            hard_coded.append(f"{source.relative_to(ROOT)}:{line_number}")
require(not hard_coded, "hard-coded production UI labels: " + ", ".join(hard_coded))

xml_literals = []
for source in RES.rglob("*.xml"):
    if source.parent.name.startswith("values"):
        continue
    for element in ET.parse(source).getroot().iter():
        for attribute in ("text", "hint", "contentDescription", "label", "title"):
            value = element.get(ANDROID_NS + attribute)
            if value and not value.startswith(("@", "?")):
                xml_literals.append(f"{source.relative_to(ROOT)}:{attribute}")
require(not xml_literals, "hard-coded production XML labels: " + ", ".join(xml_literals))

print(f"PASS: {len(english_keys)} production strings complete in {', '.join(LOCALES)} with matching placeholders")
print(f"PASS: Android locale metadata and {len(notice_ids)} localized notice descriptions")
print("PASS: complete locales are packaged together; no hard-coded production UI labels")
