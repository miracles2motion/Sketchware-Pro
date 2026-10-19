#!/usr/bin/env python3
"""Cheap stand-in for javac when there is no Android SDK around.

Gradle needs dl.google.com, which this sandbox cannot reach, so a real
compile is impossible here. What we can still catch locally are the two
mistakes that actually broke CI before:

  * a type used without being imported  -> "cannot find symbol"
  * a helper called that the file never declares (and no import provides)
    -> "cannot find symbol"

It is a checklist, not a gate: it prints candidates and you judge them.
Run:  python3 scripts/check_symbols.py <file.java> ...
"""

import os
import re
import sys

PRIMITIVES = {
    "boolean", "byte", "char", "short", "int", "long", "float", "double",
    "void", "var",
}

JAVA_LANG = {
    "Object", "String", "Integer", "Boolean", "Character", "Byte", "Short",
    "Long", "Float", "Double", "Number", "Math", "System", "Exception",
    "RuntimeException", "IllegalStateException", "IllegalArgumentException",
    "UnsupportedOperationException", "InterruptedException", "Thread",
    "Runnable", "StringBuilder", "StringBuffer", "CharSequence", "Comparable",
    "Iterable", "Override", "Deprecated", "SuppressWarnings", "SafeVarargs",
    "FunctionalInterface", "Error", "Throwable", "AssertionError", "NumberFormatException",
    "NullPointerException", "IndexOutOfBoundsException", "ClassCastException",
}

# Shapes a type name appears in.
# (?<![.\w]) keeps fully-qualified names (android.widget.LinearLayout)
# from being read as a bare LinearLayout.
TYPE_SHAPES = [
    re.compile(r"(?<![.\w])new\s+([A-Z][A-Za-z0-9_]*)"),
    re.compile(r"\(([A-Z][A-Za-z0-9_]*)\)\s*[A-Za-z(]"),
    re.compile(r"(?<![.\w])([A-Z][A-Za-z0-9_]*)\s*<[A-Za-z?]"),
    re.compile(r"(?<![.\w])([A-Z][A-Za-z0-9_]*)\s*\[\]\s*[A-Za-z)]"),
    re.compile(r"(?<![.\w])([A-Z][A-Za-z0-9_]*)\s+[a-z_][A-Za-z0-9_]*"
               r"\s*(?:=|;|\))"),
    re.compile(r"throws\s+([A-Z][A-Za-z0-9_]*)"),
    re.compile(r"extends\s+([A-Z][A-Za-z0-9_]*)"),
    re.compile(r"implements\s+([A-Z][A-Za-z0-9_]*)"),
    re.compile(r"catch\s*\(\s*([A-Z][A-Za-z0-9_]*)"),
    re.compile(r"instanceof\s+([A-Z][A-Za-z0-9_]*)"),
    # for (Type name : list) — the shape that hid a missing JsonElement
    re.compile(r"(?<![.\w])for\s*\(\s*([A-Z][A-Za-z0-9_]*)\s+"
               r"[a-z_][A-Za-z0-9_]*\s*:"),
    # parameters: (Type name, ...) and (Type name)
    re.compile(r"\(\s*([A-Z][A-Za-z0-9_]*)\s+[a-z_][A-Za-z0-9_]*"
               r"\s*[,)]"),
    re.compile(r",\s*([A-Z][A-Za-z0-9_]*)\s+[a-z_][A-Za-z0-9_]*"
               r"\s*[,)]"),
    # returns and fields: Type name; at any indent
    re.compile(r"(?<![.\w])([A-Z][A-Za-z0-9_]*)\s+[a-z_][A-Za-z0-9_]*"
               r"\s*;"),
]

CALL = re.compile(r"(?<![.\w])([a-z][A-Za-z0-9_]*)\s*\(")

# Methods that arrive from the JDK, Android or Gson, not from this file.
COMMON_CALLS = set("""
if for while switch catch return new try synchronized else do throw instanceof
super this assert
get set add put remove size length isEmpty contains clear equals hashCode
toString matches replace trim split valueOf parseInt parseDouble parseFloat
parseColor parseString format append substring startsWith endsWith
toLowerCase toUpperCase charAt indexOf lastIndexOf concat isBlank isEmpty
has getAsString getAsInt getAsBoolean getAsDouble getAsJsonObject
getAsJsonArray getAsJsonPrimitive isJsonObject isJsonArray isJsonPrimitive
isJsonNull addProperty addAll removeAll retainAll containsKey containsValue
entrySet keySet values println print sleep wait notify start run join
interrupt countDown await show dismiss setTitle setMessage setPositiveButton
setNegativeButton setNeutralButton setItems setView setCancelable
setOnCancelListener setOnDismissListener findViewById inflate setText
getText setOnClickListener setVisibility setEnabled setPadding
setBackgroundColor setTextColor setOrientation addView removeAllViews
setLayoutParams setElevation setRadius newBuilder build create from to now
currentTimeMillis nextInt nextLong nextBoolean abs min max round sort
reverse shuffle binarySearch fill copyOf asList requireNonNull
post postDelayed runOnUiThread invalidate requestLayout open read write
close flush delete exists mkdirs getName getPath getAbsolutePath listFiles
encodeToString decode toJson fromJson compareTo equalsIgnoreCase
notifyDataSetChanged notifyItemChanged notifyItemInserted notifyItemRemoved
notifyItemRangeChanged setAdapter getAdapter replaceAll find group reset
schedule cancel isCancelled isDone setSpan getSpans incrementAndGet
getAndIncrement getAndSet accept apply test peek poll offer push pop
mkdir getParentFile toPath resolve relativize normalize startsWith
getContext requireContext getActivity requireActivity isAdded isVisible
isResumed isDetached getView requireView getString getText getColor
getDrawable getResources getLayoutInflater getArguments getDialog
getChildFragmentManager getParentFragmentManager getSupportFragmentManager
dismiss dismissAllowingStateLoss show showNow setCancelable setCanceledOnTouchOutside
getListView setSelection setAdapter getAdapter notifyDataSetChanged
setOnItemSelectedListener setOnItemClickListener getItemAtPosition
commit commitNow replace add remove findFragmentByTag startActivity
startActivityForResult startActivityAndCollapse
inflate setGravity setSingleLine setHint setError setInputType
setImageDrawable setImageResource setScaleType setCardBackgroundColor
setRadius setCardElevation setStrokeColor setStrokeWidth setRippleColor
addTextChangedListener setOnEditorActionListener setImeOptions
setTypeface setPaintFlags setLetterSpacing setLineSpacing
setOnCheckedChangeListener setOnTouchListener setOnLongClickListener
setClipToOutline setOutlineProvider setTranslationZ
""".split())


def strip_comments_and_strings(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"(\\.|[^"\\])*"', '""', text)
    text = re.sub(r"'(\\.|[^'\\])*'", "''", text)
    return text


def imported_names(text):
    names = set()
    for match in re.finditer(r"^import\s+(?:static\s+)?([\w.]+);",
                             text, flags=re.M):
        names.add(match.group(1).split(".")[-1])
    return names


def declared_names(text):
    """Types this file declares, plus its own method names."""
    types, methods = set(), set()
    for match in re.finditer(
            r"\b(?:class|interface|enum|record)\s+([A-Z][A-Za-z0-9_]*)", text):
        types.add(match.group(1))
    # Method declarations. The return type is one identifier, optionally
    # generic (spaces allowed inside <>) or an array — deliberately NOT
    # "anything then a name then a paren", because that also matches a
    # call site like  String key = stringOf(args, "key");  and would hide
    # exactly the mistake this script exists to catch.
    for match in re.finditer(
            r"^\s+(?:(?:public|private|protected|static|final|synchronized|"
            r"abstract|default|native)\s+)*"
            r"[A-Za-z_][A-Za-z0-9_.]*(?:\s*<[^;{}]*>)?(?:\s*\[\])?\s+"
            r"([a-z][A-Za-z0-9_]*)\s*\([^;{]*\)"
            r"(?:\s+throws\s+[\w.,\s]+)?\s*[{;]", text, flags=re.M):
        methods.add(match.group(1))

    return types, methods


def same_package_types(path):
    folder = os.path.dirname(path)
    found = set()
    if not os.path.isdir(folder):
        return found
    for name in os.listdir(folder):
        if not name.endswith(".java"):
            continue
        try:
            with open(os.path.join(folder, name), encoding="utf-8",
                      errors="ignore") as handle:
                head = handle.read()
        except OSError:
            continue
        for match in re.finditer(
                r"\b(?:public\s+|final\s+)?(?:class|interface|enum|record)\s+"
                r"([A-Z][A-Za-z0-9_]*)", head):
            found.add(match.group(1))
    return found


def check(path):
    with open(path, encoding="utf-8", errors="ignore") as handle:
        raw = handle.read()
    body = strip_comments_and_strings(raw)
    # drop import lines, they are not usage
    body = re.sub(r"^import\s+[\w.]+;", " ", body, flags=re.M)

    known = (PRIMITIVES | JAVA_LANG | imported_names(raw)
             | declared_names(raw)[0] | same_package_types(path))
    types, methods = declared_names(raw)

    problems = []
    seen = set()

    for match in CALL.finditer(body):
        name = match.group(1)
        before = body[:match.start()].rstrip()
        if before.endswith("new"):
            continue
        if name in methods or name in COMMON_CALLS:
            continue
        line = body[:match.start()].count("\n") + 1
        problems.append((line, "no such method in this file", name + "()"))
    for shape in TYPE_SHAPES:
        for match in shape.finditer(body):
            name = match.group(1)
            if name in known or name in seen:
                continue
            line = body[:match.start()].count("\n") + 1
            seen.add(name)
            problems.append((line, "type not imported", name))
    return problems


def main():
    exit_code = 0
    for path in sys.argv[1:]:
        problems = check(path)
        if not problems:
            print("ok   %s" % path)
            continue
        exit_code = 1
        print("FAIL %s" % path)
        for line, kind, name in sorted(problems):
            print("     %s:%d  %s: %s" % (path, line, kind, name))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
