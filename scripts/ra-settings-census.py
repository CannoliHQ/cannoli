#!/usr/bin/env python3
import pathlib
import re
import sys

RA = pathlib.Path(__file__).resolve().parent.parent / "retroarch"

def enum_to_key():
    out = {}
    text = (RA / "msg_hash_lbl_str.h").read_text(errors="replace")
    for m in re.finditer(r'#define\s+MENU_ENUM_LABEL_(\w+?)_STR\s+"([^"]*)"', text):
        out[m.group(1)] = m.group(2)
    return out

CONFIG_MACRO = re.compile(r'\bCONFIG_(BOOL|UINT|INT|FLOAT|SIZE|STRING|STRING_OPTIONS|PATH|DIR|HEX|ACTION)(?:_ALT)?\s*\(')

def guards_by_line(text):
    stack, out = [], []
    for line in text.split("\n"):
        s = line.strip()
        if re.match(r'#\s*if(n?def)?\b', s):
            stack.append(re.sub(r'^#\s*', '', s)[:70])
        elif re.match(r'#\s*endif\b', s) and stack:
            stack.pop()
        elif re.match(r'#\s*el(se|if)\b', s) and stack:
            stack[-1] = "else of " + stack[-1]
        out.append(stack[-1] if stack else "")
    return out

def registered():
    text = (RA / "menu" / "menu_setting.c").read_text(errors="replace")
    lines = text.split("\n")
    guards = guards_by_line(text)
    found = {}
    for i, line in enumerate(lines):
        m = CONFIG_MACRO.search(line)
        if not m:
            continue
        window = "\n".join(lines[i:i + 14])
        lm = re.search(r'MENU_ENUM_LABEL_(\w+?),', window)
        if not lm:
            continue
        name = lm.group(1)
        if name.startswith("VALUE_") or name in found:
            continue
        found[name] = (m.group(1), guards[i])

    for m in re.finditer(r'\.name_enum_idx\s*=\s*MENU_ENUM_LABEL_(\w+?);', text):
        name = m.group(1)
        if name.startswith("VALUE_") or name in found:
            continue
        i = text.count("\n", 0, m.start())
        found[name] = ("STRING_OPTIONS", guards[i])
    return found

def cpp_by_line(text):
    stack, out = [], []
    for line in text.split("\n"):
        t = line.strip()
        if re.match(r'#\s*if(n?def)?\b', t):
            stack.append(re.sub(r'^#\s*', '', t)[:70])
        elif re.match(r'#\s*endif\b', t) and stack:
            stack.pop()
        elif re.match(r'#\s*el(se|if)\b', t) and stack:
            stack[-1] = "else of " + stack[-1]
        out.append(stack[-1] if stack else "")
    return out

S_ROW = re.compile(r'^S_(BOOL|UINT|INT|FLOAT|STRING|PATH|DIR|ACTION|SIZE|HEX)[A-Z0-9_]*\s*\(')

def def_file_rows(text):
    lines = text.split("\n")
    cpp = cpp_by_line(text)
    for i, line in enumerate(lines):
        m = S_ROW.match(line)
        if not m:
            continue
        chunk, depth = [], 0
        for j in range(i, len(lines)):
            chunk.append(lines[j])
            depth += lines[j].count("(") - lines[j].count(")")
            if depth <= 0:
                break
        body = "\n".join(chunk)
        km = re.search(r'"([^"]*)"', body)
        if not km:
            continue
        pre = body[:km.start()]
        ids = re.findall(r'\b([A-Z][A-Z0-9_]*)\b', pre)
        if not ids:
            continue
        yield km.group(1), ids[-1], m.group(1), cpp[i]

def from_def_files(text, guards):
    found = {}
    for m in re.finditer(r'#\s*include\s+"\.\./settings/(settings_def_\w+\.h)"', text):
        name = m.group(1)
        path = RA / "settings" / name
        if not path.exists():
            continue
        i = text.count("\n", 0, m.start())
        outer_cpp = guards[i]
        for key, enum, kind, inner_cpp in def_file_rows(path.read_text(errors="replace")):
            if enum in found:
                continue
            parts = [g for g in (outer_cpp, inner_cpp) if g]
            cpp = " && ".join(dict.fromkeys(parts))
            found[enum] = (key, kind, cpp)
    return found

def screens():
    text = (RA / "menu" / "menu_displaylist.c").read_text(errors="replace")
    out = {}
    for block in re.finditer(r'case DISPLAYLIST_(\w+?)_SETTINGS_LIST:(.*?)(?=\n\s*case DISPLAYLIST_|\Z)',
                             text, re.S):
        screen = block.group(1)
        for lm in re.finditer(r'MENU_ENUM_LABEL_(\w+?),', block.group(2)):
            out.setdefault(lm.group(1), screen)
    return out

def main():
    keys, reg, scr = enum_to_key(), registered(), screens()
    ms = (RA / "menu" / "menu_setting.c").read_text(errors="replace")
    defs = from_def_files(ms, guards_by_line(ms))
    rows = []
    for enum, (key, kind, cpp) in defs.items():
        rows.append((key, kind, scr.get(enum, ""), cpp))
    for enum, (kind, cpp) in reg.items():
        if enum in defs:
            continue
        key = keys.get(enum)
        if not key:
            continue
        rows.append((key, kind, scr.get(enum, ""), cpp))
    rows.sort()
    out = ["config_key\ttype\tscreen\tcpp_guard"]
    out += ["\t".join(r) for r in rows]
    dest = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else None
    body = "\n".join(out) + "\n"
    if dest:
        dest.write_text(body)
        print(f"{len(rows)} settings -> {dest}")
    else:
        sys.stdout.write(body)

main()
