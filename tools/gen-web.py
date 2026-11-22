#!/usr/bin/env python3
"""把 js/chip.js 与 js/panel.js 生成成 Java 里的注入方法。

用法：python3 tools/gen-web.py
- js/chip.js  → Probe.java 的 engineLabelJs(String version)
  chip.js 里以 `})(__V__)` 结尾，替换成 `})(\n + v + )\n`。
- js/panel.js → EnginePanel.java 的 js()
生成后可直接 grep 校验，不再手抄字符串（1.3.58 那次是手抄的，容易走样）。
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/dev/dsh/harness"


def jline(line: str) -> str:
    """一行 JS → Java 字符串字面量（含结尾 \\n）。"""
    out = line.replace("\\", "\\\\").replace('"', '\\"')
    return '                + "%s\\n"\n' % out


def gen_chip() -> str:
    src = (ROOT / "js/chip.js").read_text(encoding="utf-8")
    if not src.rstrip().endswith("})(__V__)"):
        raise SystemExit("chip.js 必须以 })(__V__) 结尾")
    body = src.rstrip()[: -len("})(__V__)")]
    lines = body.splitlines()
    parts = ['        String v = Utils.jsStr(version);\n', '        return "" \n']
    for ln in lines:
        parts.append(jline(ln))
    parts.append('                + "})(\\n"\n')
    parts.append('                + v\n')
    parts.append('                + ")\\n"\n')
    parts.append('                + "";\n')
    return "".join(parts)


def gen_panel() -> str:
    src = (ROOT / "js/panel.js").read_text(encoding="utf-8")
    parts = ['        return "" \n']
    for ln in src.rstrip().splitlines():
        parts.append(jline(ln))
    parts.append('                + "";\n')
    return "".join(parts)


def replace_method(path: pathlib.Path, signature: str, body: str) -> None:
    text = path.read_text(encoding="utf-8")
    start = text.index(signature)
    brace = text.index("{", start)
    depth = 0
    i = brace
    while i < len(text):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                break
        elif c == '"':
            i += 1
            while i < len(text):
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    break
                i += 1
        i += 1
    end = i + 1
    new = text[:brace] + "{\n" + body + "    }" + text[end:]
    path.write_text(new, encoding="utf-8")
    print("updated", path.name, "method", signature.split("(")[0].split()[-1])


replace_method(JAVA / "Probe.java", "static String engineLabelJs(String version)", gen_chip())
replace_method(JAVA / "EnginePanel.java", "static String js()", gen_panel())
