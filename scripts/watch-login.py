#!/usr/bin/env python3
"""Sign a Wear OS watch running Claude for Watch in from this computer, over ADB.

A watch has no clipboard, so typing a 48-character OAuth code or an API key on it is
miserable. This script does the browser part on the computer and pushes the result to the
app's ProvisionReceiver with `adb shell am broadcast` (no server, no phone app).
Python 3.8+, standard library only, Linux / macOS / Windows.

Modes
  (default) --login   Full PKCE sign-in on this computer: opens claude.ai in your browser,
                      catches the redirect on http://localhost:<port>/callback, exchanges the
                      code, and sends the PROTOCOL.md §1.1 credentials record to the watch
                      (`--es credentials_b64`). If the browser cannot reach localhost, paste
                      the code instead (both URLs are printed).
  --code "CODE#STATE" Finish the QR sign-in the *watch* is showing: scan its QR on any device,
                      sign in, and pass the code that platform.claude.com shows. The watch
                      holds the PKCE verifier, so the code is forwarded as-is (`oauth_code`).
  --api-key KEY       Store a Console API key (`api_key`). Use `--api-key -` to type it
                      without it landing in your shell history.
  --print-only        Print the base64 credentials record instead of running adb (for manual
                      `adb shell am broadcast ... --es credentials_b64 <b64>`). It contains your
                      tokens: treat the output like a password.
  --self-test         Run the built-in unit tests for the PKCE / URL / code-parsing helpers.

Examples
  python3 scripts/watch-login.py                          # sign in, push to the only device
  python3 scripts/watch-login.py --serial 192.168.1.20:41235
  python3 scripts/watch-login.py --code 'abc...#xyz...'
  python3 scripts/watch-login.py --api-key -
  python3 scripts/watch-login.py --package com.claudeforwatch   # a store *debug* build (API key only)

Notes
  * --login mints a token pair that is separate from Claude Code's own login on this
    computer. The watch rotates its refresh token on every refresh; because the pairs are
    independent, that never invalidates the computer's `claude` CLI session (copying
    ~/.claude/.credentials.json to the watch instead would: the first refresh on either side
    logs the other out).
  * It uses Claude Code's public OAuth client id. Anthropic does not support third-party apps
    using it and may block or suspend accounts that do (docs/PROTOCOL.md §1.2). Personal use
    with your own account only. The supported option is an API key.
  * The receiver exists only in the `personal` flavor and in debug builds; the store release
    APK ignores this. Tokens and keys are never printed (except with --print-only). The adb
    command line carries the value as an argument, so it is briefly visible to other local
    users in `ps` on this computer; run it on a machine you trust.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import hmac
import http.server
import json
import os
import queue
import re
import secrets
import shlex
import shutil
import socketserver
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from typing import Dict, List, Optional, Tuple

AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
MANUAL_REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
SCOPES = ["user:profile", "user:inference", "user:sessions:claude_code"]
OAUTH_BETA = "oauth-2025-04-20"
USER_AGENT = "ClaudeForWatch/0.1.0 (watch-login.py)"

ACTION = "com.claudeforwatch.PROVISION"
RECEIVER = "com.claudeforwatch.provision.ProvisionReceiver"
DEFAULT_PACKAGE = "com.claudeforwatch.personal"
LOGIN_TIMEOUT_S = 600  # the watch-side PKCE TTL is 10 minutes too (PROTOCOL §2)


class LoginError(Exception):
    """User-facing failure. Messages never contain secrets."""


# --------------------------------------------------------------------------- PKCE (§2)

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def challenge_for(verifier: str) -> str:
    return b64url(hashlib.sha256(verifier.encode("ascii")).digest())


def generate_pkce() -> Tuple[str, str, str]:
    """(verifier, challenge, state): verifier/state = base64url(32 random bytes), 43 chars."""
    verifier = b64url(secrets.token_bytes(32))
    state = b64url(secrets.token_bytes(32))
    return verifier, challenge_for(verifier), state


def authorize_url(challenge: str, state: str, redirect_uri: str) -> str:
    """Parameters in PROTOCOL §2 order, form-encoded like URLSearchParams (space -> '+')."""
    params = [
        ("code", "true"),
        ("client_id", CLIENT_ID),
        ("response_type", "code"),
        ("redirect_uri", redirect_uri),
        ("scope", " ".join(SCOPES)),
        ("code_challenge", challenge),
        ("code_challenge_method", "S256"),
        ("state", state),
    ]
    return AUTHORIZE_URL + "?" + urllib.parse.urlencode(params)


def parse_code(text: str) -> Tuple[str, Optional[str], Optional[str]]:
    """Parse what the user pastes. Returns (code, state, redirect_uri_hint).

    Accepts `<code>#<state>` (shown by platform.claude.com -> manual redirect URI), a full
    callback URL (`http://localhost:N/callback?code=..&state=..` -> that redirect URI), or a
    bare code (state None). Whitespace is ignored.
    """
    t = "".join(text.split())
    if not t:
        raise LoginError("empty code")
    if t.startswith("http://") or t.startswith("https://"):
        parsed = urllib.parse.urlsplit(t)
        q = urllib.parse.parse_qs(parsed.query)
        if "error" in q:
            raise LoginError("authorization failed: " + q["error"][0])
        code = (q.get("code") or [""])[0]
        if not code:
            raise LoginError("that URL has no ?code=")
        redirect = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", ""))
        return code, (q.get("state") or [None])[0], redirect
    if "#" in t:
        code, state = t.split("#", 1)
        if not code:
            raise LoginError("that doesn't look like a sign-in code")
        return code, state, MANUAL_REDIRECT_URI
    return t, None, None


def check_state(got: Optional[str], expected: str) -> None:
    if got is None or not hmac.compare_digest(got.encode(), expected.encode()):
        raise LoginError("state mismatch: that code belongs to a different sign-in. Start again.")


# --------------------------------------------------------------------------- token exchange

def _post(fields: Dict[str, str], form: bool, timeout: float = 30) -> Tuple[int, dict]:
    if form:
        body = urllib.parse.urlencode(fields).encode()
        ctype = "application/x-www-form-urlencoded"
    else:
        body = json.dumps(fields).encode()
        ctype = "application/json"
    req = urllib.request.Request(TOKEN_URL, data=body, method="POST", headers={
        "Content-Type": ctype,
        "anthropic-beta": OAUTH_BETA,
        "User-Agent": USER_AGENT,
        "Accept": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        raw = e.read()
        status = e.code
    except urllib.error.URLError as e:
        raise LoginError("could not reach platform.claude.com: %s" % (e.reason,))
    try:
        data = json.loads(raw.decode("utf-8") or "{}")
    except ValueError:
        data = {}
    return status, data if isinstance(data, dict) else {}


def error_code(data: dict) -> Optional[str]:
    err = data.get("error")
    if isinstance(err, dict):
        return err.get("type") or err.get("message")
    return err if isinstance(err, str) else None


def exchange(code: str, state: str, verifier: str, redirect_uri: str) -> dict:
    """PROTOCOL §2: JSON first; on 400 invalid_grant retry once form-encoded."""
    fields = {
        "grant_type": "authorization_code",
        "code": code,
        "state": state,
        "client_id": CLIENT_ID,
        "redirect_uri": redirect_uri,
        "code_verifier": verifier,
    }
    status, data = _post(fields, form=False)
    if status == 400 and error_code(data) == "invalid_grant":
        status, data = _post(fields, form=True)
    if status != 200 or not data.get("access_token"):
        raise LoginError("token exchange failed (HTTP %d%s)" % (status, ", " + str(error_code(data)) if error_code(data) else ""))
    return data


def credentials_record(token: dict, now_ms: int) -> dict:
    """PROTOCOL §1.1 record from a token response; expiresAt keeps 60 s of margin."""
    scope = token.get("scope")
    org = token.get("organization") or {}
    account = token.get("account") or {}
    return {
        "mode": "claudeAccount",
        "accessToken": token["access_token"],
        "refreshToken": token.get("refresh_token"),
        "expiresAt": now_ms + int(token.get("expires_in") or 0) * 1000 - 60000,
        "scopes": scope.split() if isinstance(scope, str) and scope.strip() else list(SCOPES),
        "organizationUuid": org.get("uuid") if isinstance(org, dict) else None,
        "accountEmail": account.get("email_address") if isinstance(account, dict) else None,
        "apiKey": None,
    }


def encode_record(record: dict) -> str:
    return base64.b64encode(json.dumps(record, separators=(",", ":")).encode("utf-8")).decode("ascii")


# --------------------------------------------------------------------------- browser login

_PAGE = (b"<!doctype html><meta charset=utf-8><title>Claude for Watch</title>"
         b"<body style='font:16px system-ui;margin:3em;text-align:center'>"
         b"<h2>%s</h2><p>%s</p></body>")


def _make_handler(results: "queue.Queue[Tuple[str, object]]"):
    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path != "/callback":
                self.send_response(404)
                self.end_headers()
                return
            q = urllib.parse.parse_qs(parsed.query)
            ok = "code" in q and "error" not in q
            body = _PAGE % ((b"Signed in", b"You can close this tab and look at your watch.") if ok else
                            (b"Sign-in failed", b"Go back to the terminal."))
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            results.put(("callback", q))

        def log_message(self, *args):  # keep the code out of the terminal
            pass

    return Handler


class _Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def _read_stdin(results: "queue.Queue[Tuple[str, object]]") -> None:
    try:
        line = sys.stdin.readline()
    except Exception:
        return
    if line.strip():
        results.put(("pasted", line))


def browser_login(open_browser: bool, timeout_s: int) -> dict:
    verifier, challenge, state = generate_pkce()
    results: "queue.Queue[Tuple[str, object]]" = queue.Queue()
    server = _Server(("127.0.0.1", 0), _make_handler(results))
    port = server.server_address[1]
    local_redirect = "http://localhost:%d/callback" % port
    auto_url = authorize_url(challenge, state, local_redirect)
    manual_url = authorize_url(challenge, state, MANUAL_REDIRECT_URI)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        print("Opening your browser to sign in to Claude. If it doesn't open, visit:\n\n  %s\n" % auto_url)
        print("If that page can't reach localhost (other machine, SSH), open this one instead,\n"
              "then paste the code it shows (code#state) here and press Enter:\n\n  %s\n" % manual_url)
        if open_browser:
            try:
                webbrowser.open(auto_url)
            except Exception:
                pass
        if sys.stdin is not None:
            threading.Thread(target=_read_stdin, args=(results,), daemon=True).start()
        print("Waiting for the browser (or a pasted code)... ", end="", flush=True)
        try:
            kind, payload = results.get(timeout=timeout_s)
        except queue.Empty:
            raise LoginError("timed out after %d s waiting for sign-in" % timeout_s)
        print()
        if kind == "callback":
            q = payload  # type: ignore[assignment]
            if "error" in q:  # type: ignore[operator]
                raise LoginError("authorization failed: %s" % q["error"][0])  # type: ignore[index]
            code = q.get("code", [""])[0]  # type: ignore[union-attr]
            got_state = (q.get("state") or [None])[0]  # type: ignore[union-attr]
            redirect = local_redirect
        else:
            code, got_state, hint = parse_code(str(payload))
            redirect = hint or MANUAL_REDIRECT_URI
            if got_state is None:
                got_state = state  # a bare code typed by the person who started this login
        check_state(got_state, state)
        if not code:
            raise LoginError("the redirect had no code")
        print("Exchanging the code... ", end="", flush=True)
        token = exchange(code, state, verifier, redirect)
        print("done.")
        return token
    finally:
        server.shutdown()
        server.server_close()


# --------------------------------------------------------------------------- adb

def find_adb(explicit: Optional[str]) -> str:
    if explicit:
        return explicit
    found = shutil.which("adb")
    if found:
        return found
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            cand = os.path.join(root, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
            if os.path.exists(cand):
                return cand
    raise LoginError("adb not found. Install Android platform-tools or pass --adb PATH.")


def adb_base(adb: str, serial: Optional[str]) -> List[str]:
    return [adb] + (["-s", serial] if serial else [])


def check_device(adb: str, serial: Optional[str], package: str) -> None:
    r = subprocess.run(adb_base(adb, serial) + ["get-state"], capture_output=True, text=True)
    if r.returncode != 0 or r.stdout.strip() != "device":
        detail = (r.stderr or r.stdout).strip().splitlines()
        raise LoginError("no ADB device%s (%s). Connect the watch (adb connect IP:PORT) or pass --serial."
                         % (" " + serial if serial else "", detail[-1] if detail else "not connected"))
    r = subprocess.run(adb_base(adb, serial) + ["shell", "pm", "path", package], capture_output=True, text=True)
    if "package:" not in r.stdout:
        raise LoginError("%s is not installed on the watch. Install the personal (or a debug) build, "
                         "or pass --package." % package)


def broadcast_command(package: str, extra: str, value: str) -> str:
    """The device-side shell command; the value is single-quoted for the watch's sh."""
    return "am broadcast -a %s --include-stopped-packages -n %s/%s --es %s %s" % (
        ACTION, package, RECEIVER, extra, shlex.quote(value))


_RESULT_RE = re.compile(r'Broadcast completed: result=(-?\d+)(?:, data="(.*)")?')


def parse_broadcast_result(output: str) -> Tuple[Optional[int], Optional[str], Optional[str]]:
    """(result code, data, the full 'Broadcast completed' line) from `am broadcast` output."""
    for line in output.splitlines():
        m = _RESULT_RE.search(line)
        if m:
            return int(m.group(1)), m.group(2), line.strip()
    return None, None, None


def send(adb: str, serial: Optional[str], package: str, extra: str, value: str) -> bool:
    r = subprocess.run(adb_base(adb, serial) + ["shell", broadcast_command(package, extra, value)],
                       capture_output=True, text=True, timeout=90)
    code, data, line = parse_broadcast_result(r.stdout)
    if line is None:
        err = (r.stderr or r.stdout).strip().splitlines()
        print("adb broadcast failed: %s" % (err[-1] if err else "no output"), file=sys.stderr)
        return False
    print(line)
    if data is None:
        print("No reply from the app: the receiver is not in this build (store release APKs don't have it) "
              "or --package is wrong.", file=sys.stderr)
        return False
    return code == 0 and data.startswith("ok:")


# --------------------------------------------------------------------------- self-test

def self_test() -> int:
    import unittest

    class T(unittest.TestCase):
        def test_rfc7636_vector(self):
            self.assertEqual(challenge_for("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
                             "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")

        def test_generated_pkce(self):
            for _ in range(50):
                v, c, s = generate_pkce()
                for x in (v, c, s):
                    self.assertEqual(len(x), 43)
                    self.assertRegex(x, r"^[A-Za-z0-9_-]{43}$")
                self.assertEqual(c, challenge_for(v))

        def test_authorize_url_order_and_encoding(self):
            self.assertEqual(
                authorize_url("CH", "ST", "http://localhost:5555/callback"),
                "https://claude.ai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e"
                "&response_type=code&redirect_uri=http%3A%2F%2Flocalhost%3A5555%2Fcallback"
                "&scope=user%3Aprofile+user%3Ainference+user%3Asessions%3Aclaude_code"
                "&code_challenge=CH&code_challenge_method=S256&state=ST")
            # Same form as the watch's QR (android core PkceTest).
            self.assertIn("redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback",
                          authorize_url("CH", "ST", MANUAL_REDIRECT_URI))

        def test_parse_code_hash(self):
            self.assertEqual(parse_code(" abc#xyz \n"), ("abc", "xyz", MANUAL_REDIRECT_URI))
            self.assertEqual(parse_code("ab c#x yz"), ("abc", "xyz", MANUAL_REDIRECT_URI))

        def test_parse_code_url(self):
            self.assertEqual(parse_code("http://localhost:1234/callback?code=a%2Bb&state=s1"),
                             ("a+b", "s1", "http://localhost:1234/callback"))
            with self.assertRaises(LoginError):
                parse_code("http://localhost:1234/callback?error=access_denied")
            with self.assertRaises(LoginError):
                parse_code("http://localhost:1234/callback?state=s")

        def test_parse_code_bare_and_empty(self):
            self.assertEqual(parse_code("abc"), ("abc", None, None))
            with self.assertRaises(LoginError):
                parse_code("   ")
            with self.assertRaises(LoginError):
                parse_code("#state")

        def test_check_state(self):
            check_state("s", "s")
            with self.assertRaises(LoginError):
                check_state("t", "s")
            with self.assertRaises(LoginError):
                check_state(None, "s")

        def test_credentials_record(self):
            rec = credentials_record({
                "access_token": "sk-ant-oat01-a", "refresh_token": "sk-ant-ort01-r", "expires_in": 28800,
                "scope": "user:profile user:inference user:sessions:claude_code",
                "organization": {"uuid": "org-1"}, "account": {"email_address": "me@example.com"},
            }, now_ms=1_000_000)
            self.assertEqual(rec["expiresAt"], 1_000_000 + 28_800_000 - 60_000)
            self.assertEqual(rec["scopes"], SCOPES)
            self.assertEqual(rec["organizationUuid"], "org-1")
            self.assertEqual(rec["accountEmail"], "me@example.com")
            self.assertIsNone(rec["apiKey"])
            self.assertEqual(list(rec), ["mode", "accessToken", "refreshToken", "expiresAt", "scopes",
                                         "organizationUuid", "accountEmail", "apiKey"])
            back = json.loads(base64.b64decode(encode_record(rec)))
            self.assertEqual(back, rec)
            minimal = credentials_record({"access_token": "x"}, now_ms=0)
            self.assertEqual(minimal["scopes"], SCOPES)
            self.assertIsNone(minimal["organizationUuid"])

        def test_error_code(self):
            self.assertEqual(error_code({"error": "invalid_grant"}), "invalid_grant")
            self.assertEqual(error_code({"error": {"type": "invalid_grant"}}), "invalid_grant")
            self.assertIsNone(error_code({}))

        def test_broadcast_command_quotes(self):
            cmd = broadcast_command("com.claudeforwatch.personal", "oauth_code", "abc#x'y")
            self.assertTrue(cmd.startswith("am broadcast -a com.claudeforwatch.PROVISION --include-stopped-packages "
                                           "-n com.claudeforwatch.personal/com.claudeforwatch.provision.ProvisionReceiver "
                                           "--es oauth_code "))
            self.assertEqual(shlex.split(cmd)[-1], "abc#x'y")

        def test_parse_broadcast_result(self):
            out = ("Broadcasting: Intent { act=com.claudeforwatch.PROVISION flg=0x400010 (has extras) }\n"
                   'Broadcast completed: result=0, data="ok: signed in as me@example.com"\n')
            self.assertEqual(parse_broadcast_result(out)[:2], (0, "ok: signed in as me@example.com"))
            self.assertEqual(parse_broadcast_result("Broadcast completed: result=0")[:2], (0, None))
            self.assertEqual(parse_broadcast_result("Error: nope"), (None, None, None))

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(T)
    ok = unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful()
    return 0 if ok else 1


# --------------------------------------------------------------------------- main

def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(
        description="Sign Claude for Watch (Wear OS) in from this computer over ADB.",
        epilog="Personal use only: --login/--code use Claude Code's OAuth client (docs/PROTOCOL.md §1.2).")
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--login", action="store_true", help="full PKCE sign-in in this computer's browser (default)")
    mode.add_argument("--code", metavar="CODE#STATE", help="finish the QR sign-in the watch is showing")
    mode.add_argument("--api-key", metavar="KEY", help="store a Console API key ('-' to prompt)")
    mode.add_argument("--self-test", action="store_true", help="run the helper unit tests and exit")
    p.add_argument("--package", default=DEFAULT_PACKAGE, help="app id on the watch (default: %(default)s)")
    p.add_argument("--serial", help="adb device serial, e.g. 192.168.1.20:41235")
    p.add_argument("--adb", help="path to adb (default: from PATH or $ANDROID_HOME)")
    p.add_argument("--print-only", action="store_true",
                   help="print the base64 credentials record instead of calling adb (contains secrets)")
    p.add_argument("--no-browser", action="store_true", help="don't open a browser; just print the URLs")
    p.add_argument("--timeout", type=int, default=LOGIN_TIMEOUT_S, help="seconds to wait for sign-in (default: %(default)s)")
    args = p.parse_args(argv)

    if args.self_test:
        return self_test()

    try:
        adb = None
        if not args.print_only:
            adb = find_adb(args.adb)
            check_device(adb, args.serial, args.package)

        if args.api_key is not None:
            key = args.api_key
            if key == "-":
                key = getpass.getpass("API key (input hidden): ") if sys.stdin.isatty() else sys.stdin.readline()
            key = key.strip()
            if not key.startswith("sk-ant-") or any(c.isspace() for c in key):
                raise LoginError("that doesn't look like an Anthropic API key (sk-ant-...)")
            if args.print_only:
                print(encode_record({"mode": "apiKey", "accessToken": None, "refreshToken": None, "expiresAt": None,
                                     "scopes": [], "organizationUuid": None, "accountEmail": None, "apiKey": key}))
                return 0
            return 0 if send(adb, args.serial, args.package, "api_key", key) else 1

        if args.code is not None:
            if args.print_only:
                raise LoginError("--code has nothing to print: the watch holds the verifier. Drop --print-only.")
            parse_code(args.code)  # shape check only; the watch verifies the state
            return 0 if send(adb, args.serial, args.package, "oauth_code", "".join(args.code.split())) else 1

        token = browser_login(open_browser=not args.no_browser, timeout_s=args.timeout)
        record = credentials_record(token, now_ms=int(time.time() * 1000))
        b64 = encode_record(record)
        who = record.get("accountEmail") or "your account"
        if args.print_only:
            print("Signed in as %s. Credentials record (base64, contains tokens):" % who, file=sys.stderr)
            print(b64)
            return 0
        print("Signed in as %s. Sending to the watch..." % who)
        return 0 if send(adb, args.serial, args.package, "credentials_b64", b64) else 1
    except LoginError as e:
        print("\nerror: %s" % e, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\ncancelled", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
