#!/usr/bin/env python3
"""Uploads a built jar to the mod's CurseForge page, with the changelog taken from CHANGELOG.md.

    python tools/upload_curseforge.py

and paste the token at the prompt. It is not echoed, and nothing writes it down. Setting
CURSEFORGE_TOKEN in the environment works too, for CI; mind the quotes if that shell is PowerShell.

Get a token from https://legacy.curseforge.com/account/api-tokens. A CurseForge token can upload to
every project on the account, so it has no business sitting in a repository, in a shell history or
in a chat log. If one ever appears in any of those, delete it on that page and make another.

What this can and cannot do. The upload API only adds files. A project's description, its licence
and its name are web-only -- so the page still has to be edited by hand when those change, and this
script says so on the way out.

Standard library only, on purpose: a release script that needs its own dependencies installed first
is a release script that fails at the worst moment.
"""

import argparse
import getpass
import json
import mimetypes
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

API = "https://minecraft.curseforge.com/api"
PROJECT_ID = 1675569

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The three facets CurseForge wants a file tagged with, by the names it lists them under.
GAME_VERSION = "1.20.1"
MOD_LOADER = "Forge"
JAVA_VERSION = "Java 17"


TOKEN_SHAPE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                         r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


CLIPBOARD = "-"


def clipboard():
    """Whatever is on the clipboard, as text."""
    if sys.platform == "win32":
        command = ["powershell", "-NoProfile", "-Command", "Get-Clipboard -Raw"]
    elif sys.platform == "darwin":
        command = ["pbpaste"]
    else:
        command = ["xclip", "-selection", "clipboard", "-o"]

    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError) as error:
        sys.exit("Could not read the clipboard (%s). Use --token-file instead." % error)
    if done.returncode != 0:
        sys.exit("Could not read the clipboard: %s" % done.stderr.strip())
    return done.stdout.strip()


def describe(value):
    """What a token looks like, without showing it.

    Enough to tell the three failures apart -- an empty paste, a console that mangled it, and a key
    from the wrong page -- and not enough to be worth anything to anyone reading over a shoulder.
    """
    visible = "".join(ch if ch.isprintable() else "?" for ch in value)
    masked = visible if len(visible) <= 8 else visible[:4] + "..." + visible[-4:]
    return ("%d characters, looks like %s, starts/ends %s"
            % (len(value), "a UUID" if TOKEN_SHAPE.match(value) else "something else", masked))


def token(token_file=None):
    """The API token, from the environment or, failing that, typed in without being echoed.

    The prompt is the better of the two and the reason is PowerShell. A token is a UUID, and an
    unquoted UUID on a PowerShell line is not a string -- the shell reads the leading digits as a
    command and tries to run it, so the variable is never set and the script goes on to send
    whatever was there before. Typing it at a prompt has no quoting to get wrong, and leaves the
    token out of the shell's history as well.
    """
    value = os.environ.get("CURSEFORGE_TOKEN", "").strip()
    source = "CURSEFORGE_TOKEN"

    if token_file == CLIPBOARD:
        # The least that can go wrong: the token is already on the clipboard, having just been
        # copied off the CurseForge page, and this reads it without anybody having to paste it into
        # a console that may or may not accept a paste.
        value = clipboard()
        source = "the clipboard"
    elif token_file:
        # Pasting into a text editor and pointing at the file sidesteps the console entirely, which
        # is worth having: a Windows console that does not take Ctrl+V inserts a control character
        # instead of the token, and a hidden prompt gives no sign that it happened.
        if not os.path.isfile(token_file):
            sys.exit("No token file at %s" % token_file)
        with open(token_file, encoding="utf-8-sig") as handle:
            value = handle.read().strip()
        source = token_file

    if not value:
        try:
            value = getpass.getpass("CurseForge API token (not shown as you type): ").strip()
        except (EOFError, KeyboardInterrupt):
            sys.exit("\nNo token given.")
        source = "the prompt"

    if not value:
        sys.exit("Nothing was entered. If you pasted with Ctrl+V and the console ignored it, try "
                 "right-click to paste, or use --token-file.")
    if value.upper() in {"TWOJ_TOKEN", "YOUR_TOKEN", "<TOKEN>"}:
        sys.exit("That is the placeholder, not a token. Get a real one from "
                 "https://legacy.curseforge.com/account/api-tokens")

    print("token from %s: %s" % (source, describe(value)))

    if not TOKEN_SHAPE.match(value):
        print("")
        print("That is not the shape of an upload token, and CurseForge will refuse it. An upload")
        print("token is a UUID -- 36 characters, five groups of hex digits separated by dashes.")
        print("")
        print("Two keys get mixed up here, because both are called an API key:")
        print("  UPLOAD  https://legacy.curseforge.com/account/api-tokens")
        print("          A UUID. This is the one that can publish files. Use this.")
        print("  READING https://console.curseforge.com/ -> API Keys")
        print("          A long string starting with $2a$ or similar. It only reads the public")
        print("          catalogue and cannot upload anything, whatever it is pasted into.")
        print("")
        if len(value) == 1 or not value.isprintable():
            print("It also looks like the console swallowed the paste. Try --token-file instead.")
    return value


def request(path, api_token, data=None, content_type=None):
    req = urllib.request.Request(API + path, data=data)
    req.add_header("X-Api-Token", api_token)
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as response:
            body = response.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        sys.exit("CurseForge refused the request (%s %s):\n%s" % (error.code, error.reason, detail))
    except urllib.error.URLError as error:
        sys.exit("Could not reach CurseForge: %s" % error.reason)


def version_ids(api_token):
    """Turns the three version names above into the numeric ids the upload wants."""
    versions = request("/game/versions", api_token)
    wanted = {GAME_VERSION: None, MOD_LOADER: None, JAVA_VERSION: None}

    for entry in versions:
        name = entry.get("name")
        if name in wanted and wanted[name] is None:
            wanted[name] = entry.get("id")

    missing = [name for name, found in wanted.items() if found is None]
    if missing:
        sys.exit("CurseForge does not list these versions any more: %s. "
                 "Check the names at the top of this script." % ", ".join(missing))

    for name, found in wanted.items():
        print("  %-10s -> %s" % (name, found))
    return list(wanted.values())


def changelog(version):
    """The section of CHANGELOG.md for this version, verbatim.

    One source for what changed, rather than a copy pasted into the upload form and immediately out
    of date.
    """
    path = os.path.join(ROOT, "CHANGELOG.md")
    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    match = re.search(r"^## %s\s*$(.*?)(?=^## |\Z)" % re.escape(version), text,
                      re.MULTILINE | re.DOTALL)
    if not match:
        sys.exit("CHANGELOG.md has no '## %s' section to take a changelog from." % version)
    return match.group(1).strip()


def multipart(metadata, file_path):
    boundary = "----brama-" + uuid.uuid4().hex
    name = os.path.basename(file_path)
    mime = mimetypes.guess_type(name)[0] or "application/java-archive"

    with open(file_path, "rb") as handle:
        payload = handle.read()

    body = b"".join([
        ("--%s\r\n" % boundary).encode(),
        b'Content-Disposition: form-data; name="metadata"\r\n',
        b"Content-Type: application/json\r\n\r\n",
        json.dumps(metadata).encode("utf-8"),
        ("\r\n--%s\r\n" % boundary).encode(),
        ('Content-Disposition: form-data; name="file"; filename="%s"\r\n' % name).encode(),
        ("Content-Type: %s\r\n\r\n" % mime).encode(),
        payload,
        ("\r\n--%s--\r\n" % boundary).encode(),
    ])
    return body, "multipart/form-data; boundary=%s" % boundary


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", default="1.3.0", help="mod version, also the CHANGELOG heading")
    parser.add_argument("--project-id", type=int, default=PROJECT_ID)
    parser.add_argument("--file", help="jar to upload (default: build/libs/brama-<version>.jar)")
    parser.add_argument("--release-type", default="release", choices=["release", "beta", "alpha"])
    parser.add_argument("--dry-run", action="store_true",
                        help="resolve everything and print what would be sent, without sending it")
    parser.add_argument("--token-file",
                        help="read the token from this file instead of asking for it, for consoles "
                             "that will not take a pasted password; pass - to read the clipboard")
    parser.add_argument("--token-clipboard", dest="token_file", action="store_const", const=CLIPBOARD,
                        help="take the token straight off the clipboard, pasting nothing anywhere")
    parser.add_argument("--check-token", action="store_true",
                        help="say whether the token is the right shape and stop, uploading nothing")
    args = parser.parse_args()

    jar = args.file or os.path.join(ROOT, "build", "libs", "brama-%s.jar" % args.version)
    if not os.path.isfile(jar):
        sys.exit("No jar at %s - run ./gradlew build first." % jar)

    api_token = token(args.token_file)
    if args.check_token:
        return 0
    notes = changelog(args.version)

    print("uploading %s (%.1f MB) to project %d as a %s"
          % (os.path.basename(jar), os.path.getsize(jar) / 1048576.0,
             args.project_id, args.release_type))
    print("game versions:")
    metadata = {
        "changelog": notes,
        "changelogType": "markdown",
        "displayName": "Brama %s" % args.version,
        "gameVersions": version_ids(api_token),
        "releaseType": args.release_type,
    }

    if args.dry_run:
        print("\n--- metadata (not sent) ---")
        print(json.dumps(metadata, indent=2)[:2000])
        return 0

    body, content_type = multipart(metadata, jar)
    result = request("/projects/%d/upload-file" % args.project_id, api_token, body, content_type)

    file_id = result.get("id")
    print("\nuploaded as file %s" % file_id)
    print("https://www.curseforge.com/minecraft/mc-mods/brama-advanced-medieval-gates/files/%s"
          % file_id)
    print("\nStill to do by hand, because the upload API cannot touch them:")
    print("  - the project description (paste CURSEFORGE.md)")
    print("  - the licence, if it still says All Rights Reserved (this mod is MIT now)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
