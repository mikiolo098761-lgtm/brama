#!/usr/bin/env python3
"""Turns CURSEFORGE.md into the HTML the CurseForge description editor stores.

    python tools/curseforge_description.py > description.html

CurseForge keeps a project description as HTML in a TinyMCE field, and the upload API cannot touch
it, so the page has to be edited in a browser. Keeping the text in Markdown in the repository and
converting it here means the description is reviewed and versioned like everything else, instead of
living only in a web form where nobody can see it change.

A small converter rather than a Markdown library, for the same reason the upload script has no
dependencies: this handles exactly the constructs CURSEFORGE.md uses -- headings, paragraphs, rules,
bullet lists, tables, bold, inline code and links -- and nothing else. If a new construct appears in
the description, teach it here; the output is checked by reading it, because the only consumer is a
web editor that will happily render whatever it is given.
"""

import html as html_module
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "CURSEFORGE.md")


def inline(text):
    """Bold, inline code and links, in that order. Everything else is escaped first."""
    out = html_module.escape(text, quote=False)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"\[([^\]]+)\]\((https?://[^)]+)\)", r'<a href="\2" rel="nofollow">\1</a>', out)
    # Bare URLs on their own, which the Markdown leaves unlinked.
    out = re.sub(r"(?<![\"'>=])(https://[^\s<)]+[^\s<).,])", r'<a href="\1" rel="nofollow">\1</a>', out)
    return out


def table(rows):
    """A Markdown table, header row first, separator row already dropped."""
    head = rows[0]
    body = rows[1:]

    out = ["<table>", "<thead>", "<tr>"]
    out += ["<th>%s</th>" % inline(cell) for cell in head]
    out += ["</tr>", "</thead>", "<tbody>"]
    for row in body:
        out.append("<tr>")
        out += ["<td>%s</td>" % inline(cell) for cell in row]
        out.append("</tr>")
    out += ["</tbody>", "</table>"]
    return "\n".join(out)


def cells(line):
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def convert(markdown):
    lines = markdown.replace("\r\n", "\n").split("\n")
    out = []
    paragraph = []
    bullets = []
    numbers = []
    rows = []

    def flush_paragraph():
        if paragraph:
            out.append("<p>%s</p>" % inline(" ".join(paragraph).strip()))
            paragraph.clear()

    def flush_bullets():
        if bullets:
            out.append("<ul>")
            out.extend("<li>%s</li>" % inline(item) for item in bullets)
            out.append("</ul>")
            bullets.clear()

    def flush_numbers():
        if numbers:
            out.append("<ol>")
            out.extend("<li>%s</li>" % inline(item) for item in numbers)
            out.append("</ol>")
            numbers.clear()

    def flush_table():
        if rows:
            out.append(table(rows))
            rows.clear()

    def flush_all():
        flush_paragraph()
        flush_bullets()
        flush_numbers()
        flush_table()

    for raw in lines:
        line = raw.rstrip()
        stripped = line.strip()

        if not stripped:
            flush_all()
            continue

        # A table row, or the ---|--- separator under its header.
        if stripped.startswith("|") and stripped.endswith("|"):
            flush_paragraph()
            flush_bullets()
            flush_numbers()
            if set(stripped.replace("|", "").replace(" ", "")) <= {"-", ":"}:
                continue
            rows.append(cells(stripped))
            continue
        flush_table()

        if stripped.startswith("### "):
            flush_all()
            out.append("<h3>%s</h3>" % inline(stripped[4:]))
        elif stripped.startswith("## "):
            flush_all()
            out.append("<h2>%s</h2>" % inline(stripped[3:]))
        elif stripped.startswith("# "):
            flush_all()
            out.append("<h1>%s</h1>" % inline(stripped[2:]))
        elif set(stripped) <= {"-"} and len(stripped) >= 3:
            flush_all()
            out.append("<hr>")
        elif stripped.startswith("- "):
            flush_paragraph()
            flush_numbers()
            bullets.append(stripped[2:])
        elif re.match(r"^\d+\.\s", stripped):
            flush_paragraph()
            flush_bullets()
            numbers.append(re.sub(r"^\d+\.\s+", "", stripped))
        elif bullets and raw.startswith("  "):
            # Continuation of the bullet above, wrapped in the source for line length.
            bullets[-1] += " " + stripped
        elif numbers and raw.startswith("  "):
            numbers[-1] += " " + stripped
        else:
            flush_bullets()
            flush_numbers()
            paragraph.append(stripped)

    flush_all()
    return "\n".join(out) + "\n"


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    with open(SOURCE, encoding="utf-8") as handle:
        markdown = handle.read()
    sys.stdout.write(convert(markdown))
    return 0


if __name__ == "__main__":
    sys.exit(main())
