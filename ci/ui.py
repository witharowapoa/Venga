"""Helpers for the emulator smoke test, reading a uiautomator XML dump.

  ui.py tap   dump.xml "text"   -> prints "x y" centre of the first node whose text contains "text"
  ui.py after dump.xml "text"   -> prints the text of the first text node after the node containing "text"
  ui.py has   dump.xml "text"   -> exit 0 if any node's text contains "text", else 1
  ui.py answer words.txt "English prompt" -> prints the first accepted answer, adb-input-safe
"""
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET


def nodes(path):
    return [n for n in ET.parse(path).iter("node")]


def label(n):
    return (n.get("text") or "") + " " + (n.get("content-desc") or "")


def centre(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    mode, path, needle = sys.argv[1], sys.argv[2], sys.argv[3]
    if mode == "answer":
        for line in open(path, encoding="utf-8"):
            p = line.rstrip("\n").split("|")
            if len(p) >= 3 and not line.startswith("#") and p[1].strip() == needle.strip():
                a = p[2].split(";")[0].strip().lower()
                a = "".join(c for c in unicodedata.normalize("NFD", a) if unicodedata.category(c) != "Mn")
                a = re.sub(r"[^a-z0-9 ]", "", a)
                print(a.replace(" ", "%s"))
                return 0
        print("notfound")
        return 1
    ns = nodes(path)
    top, bottom = 150, centre(ns[0])[1] * 2 - 150  # stay clear of the status and navigation bars
    for i, n in enumerate(ns):
        if needle in label(n):
            if mode == "tap":
                x, y = centre(n)
                if not (top < y < bottom):
                    continue  # off screen: caller should scroll
                print(x, y)
                return 0
            if mode == "has":
                return 0
            if mode == "visible":
                if top < centre(n)[1] < bottom:
                    return 0
            if mode == "after":
                for m in ns[i + 1:]:
                    if (m.get("text") or "").strip():
                        print(m.get("text"))
                        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
