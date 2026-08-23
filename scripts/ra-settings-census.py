#!/usr/bin/env python3
"""Census every RetroArch setting the IGM could expose, from the vendored source.

Cannoli reaches a setting by its CONFIG KEY through ricotta_ra_find -> menu_setting_find, so a key
is usable only when RetroArch registers it as a menu setting on this build. Two things make that
easy to get wrong by hand, and both have shipped:

  - The menu label and the config key are different namespaces. VIDEO_ASPECT_RATIO_INDEX is
    "aspect_ratio_index"; VIDEO_HDR_ENABLE is "video_hdr_mode".
  - A key with no menu registration is dropped silently by mapNotNull, so a wrong entry looks like
    a setting that merely does not appear.

Most settings are declared once in retroarch/settings/settings_def_*.h and included twice: by
configuration.c to bind the config key, and by menu_setting.c to register the menu entry. Those rows
carry the config key as a literal, so they are read directly rather than mapped through
msg_hash_lbl_str.h. A def file configuration.c includes but menu_setting.c does not is a config key
with no menu registration, which Cannoli cannot reach at all. The rest stay imperative CONFIG_
macros in menu_setting.c and are parsed there.

Output is a TSV so a RetroArch bump produces a readable diff of what appeared, vanished, or moved.
"""
import pathlib
import re
import sys

RA = pathlib.Path(__file__).resolve().parent.parent / "retroarch"

# MENU_ENUM_LABEL_X -> the config key RetroArch stores it under.
def enum_to_key():
    out = {}
    text = (RA / "msg_hash_lbl_str.h").read_text(errors="replace")
    for m in re.finditer(r'#define\s+MENU_ENUM_LABEL_(\w+?)_STR\s+"([^"]*)"', text):
        out[m.group(1)] = m.group(2)
    return out

CONFIG_MACRO = re.compile(r'\bCONFIG_(BOOL|UINT|INT|FLOAT|SIZE|STRING|STRING_OPTIONS|PATH|DIR|HEX|ACTION)(?:_ALT)?\s*\(')

# The innermost #ifdef/#if still open at a line, plus the nearest enclosing `if (...)` that guards a
# registration at runtime. Both decide whether a key exists on a given build or device.
def guards_by_line(text):
    stack, runtime, depth, out = [], [], 0, []
    for i, line in enumerate(text.split("\n")):
        s = line.strip()
        if re.match(r'#\s*if(n?def)?\b', s):
            stack.append(re.sub(r'^#\s*', '', s)[:70])
        elif re.match(r'#\s*endif\b', s) and stack:
            stack.pop()
        elif re.match(r'#\s*el(se|if)\b', s) and stack:
            stack[-1] = "else of " + stack[-1]

        # A runtime guard only holds inside its own braces. Tracking depth stops one function's
        # `if` leaking onto settings registered further down the file, which it did.
        m = re.match(r'if\s*\((.+)\)\s*$', s)
        # `if (DEFAULT_SHOW_HIDDEN_FILES)` and friends decide an entry's default-value flag, not
        # whether it is registered, and sit one line above the next entry in the same table. Read as
        # a guard they attach to whichever setting follows, which is how game_specific_options came
        # to claim one.
        if m and m.group(1).startswith("DEFAULT_"):
            m = None
        if m and len(m.group(1)) < 90:
            # entered=False until the brace actually opens: RetroArch puts it on the next line, so
            # checking depth immediately would clear the guard before its block began.
            runtime = [[m.group(1), depth, False, i]]
        depth += line.count("{") - line.count("}")
        # Back at file scope means we left the function entirely, so nothing can still be guarded.
        if depth <= 0:
            depth, runtime = 0, []
        if runtime:
            if depth > runtime[0][1]:
                runtime[0][2] = True
            elif runtime[0][2]:
                runtime = []
        # Proximity cap: a guard more than 60 lines above a registration is almost certainly a
        # different construct the brace tracking failed to close, not this setting's condition.
        rt = ""
        if runtime and i - runtime[0][3] <= 60:
            rt = runtime[0][0]
        out.append((stack[-1] if stack else "", rt))
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
        # The label is the first MENU_ENUM_LABEL_ in the macro's argument list.
        window = "\n".join(lines[i:i + 14])
        lm = re.search(r'MENU_ENUM_LABEL_(\w+?),', window)
        if not lm:
            continue
        name = lm.group(1)
        if name.startswith("VALUE_") or name in found:
            continue
        cpp, rt = guards[i]
        found[name] = (m.group(1), cpp, rt)

    # The driver family is registered through a table rather than a CONFIG_ macro:
    #   string_options_entries[j].name_enum_idx = MENU_ENUM_LABEL_VIDEO_DRIVER;
    # Missing these made the census claim video_driver and audio_driver were unreachable, when both
    # are on screen. They surface as ENUM because ricotta_ra_is_combobox treats a values list that way.
    for m in re.finditer(r'\.name_enum_idx\s*=\s*MENU_ENUM_LABEL_(\w+?);', text):
        name = m.group(1)
        if name.startswith("VALUE_") or name in found:
            continue
        i = text.count("\n", 0, m.start())
        cpp, rt = guards[i]
        found[name] = ("STRING_OPTIONS", cpp, rt)
    return found

# The cpp guards open at each line of a def file. These rows carry no code, so unlike
# guards_by_line there is no runtime condition to track.
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

# Rows look like S_UINT_EX(video_aspect_ratio_idx, VIDEO_ASPECT_RATIO_INDEX, "aspect_ratio_index",
# ...). The config key is the row's first string literal and the menu enum the last bare uppercase
# identifier before it, which holds across every S_ variant: the argument lists differ in length and
# in what precedes the enum (a field name, or an offsetof with a comma of its own), never in that
# order.
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

# Def files reach the menu only where menu_setting.c includes them, and that include can sit under
# its own guard, so a row's real condition is the include site's plus its own.
def from_def_files(text, guards):
    found = {}
    for m in re.finditer(r'#\s*include\s+"\.\./settings/(settings_def_\w+\.h)"', text):
        name = m.group(1)
        path = RA / "settings" / name
        if not path.exists():
            continue
        i = text.count("\n", 0, m.start())
        outer_cpp = guards[i][0]
        # No runtime guard: the descriptor tables are static const at file scope, so there is no
        # enclosing conditional for a row to sit under. Taking one from guards[i] only picked up
        # whatever unrelated `if` happened to be within the proximity window.
        for key, enum, kind, inner_cpp in def_file_rows(path.read_text(errors="replace")):
            if enum in found:
                continue
            parts = [g for g in (outer_cpp, inner_cpp) if g]
            cpp = " && ".join(dict.fromkeys(parts))
            found[enum] = (key, kind, cpp, "")
    return found

# Which settings screen a label is listed on, from the displaylist that mentions it.
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
    for enum, (key, kind, cpp, rt) in defs.items():
        rows.append((key, enum, kind, scr.get(enum, ""), cpp, rt))
    for enum, (kind, cpp, rt) in reg.items():
        if enum in defs:
            continue
        key = keys.get(enum)
        if not key:
            continue
        rows.append((key, enum, kind, scr.get(enum, ""), cpp, rt))
    rows.sort()
    out = ["config_key\tmenu_enum\ttype\tscreen\tcpp_guard\truntime_guard"]
    out += ["\t".join(r) for r in rows]
    dest = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else None
    body = "\n".join(out) + "\n"
    if dest:
        dest.write_text(body)
        print(f"{len(rows)} settings -> {dest}")
    else:
        sys.stdout.write(body)

main()
