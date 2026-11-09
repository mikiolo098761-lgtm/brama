#!/usr/bin/env python3
"""Uploads a built jar to the mod's CurseForge page, with the changelog taken from CHANGELOG.md.

    $env:CURSEFORGE_TOKEN = '<token>'      # PowerShell
    python tools/upload_curseforge.py

    export CURSEFORGE_TOKEN='<token>'      # bash
    python3 tools/upload_curseforge.py

Get a token from https://legacy.curseforge.com/account/api-tokens. It is read from the environment
and never written anywhere: a CurseForge token can upload to every project on the account, so it has
no business sitting in a repository, in a shell history or in a chat log.

What this can and cannot do. The upload API only adds files. A project's description, its licence
and its name are web-only -- so the page still has to be edited by hand when those change, and this
script says so on the way out.

Standard library only, on purpose: a release script that needs its own dependencies installed first
is a release script that fails at the worst moment.
"""

import argparse
import json
import mimetypes
import os
import re
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


def token():
    value = os.environ.get("CURSEFORGE_TOKEN", "").strip()
    if not value:
        sys.exit("CURSEFORGE_TOKEN is not set. Get one from "
                 "https://legacy.curseforge.com/account/api-tokens and set it in this shell.")
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
    args = parser.parse_args()

    jar = args.file or os.path.join(ROOT, "build", "libs", "brama-%s.jar" % args.version)
    if not os.path.isfile(jar):
        sys.exit("No jar at %s - run ./gradlew build first." % jar)

    api_token = token()
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
