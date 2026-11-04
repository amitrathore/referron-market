#!/usr/bin/env python3
"""
Seed a :user entity in MoM's in-memory storage so player agents can join games.

MoM has no public API to create users (they normally come from Clerk OAuth).
For local dev we connect to MoM's nREPL and write directly to the storage atom.

Usage:
    python3 scripts/seed-mom-user.py

Reads MOM_OWNER_ID from .env. Idempotent — re-running overwrites the same id.

If MOM_DEV_EMAIL and MOM_DEV_PASSWORD are also set, the seeded user becomes a
login-capable native ("intergraph") user — the password is bcrypt-hashed in-JVM
(buddy.hashers) and stored as :user/password-hash, so you can sign in at the
auth UI (http://localhost:5176) with that email + password. The :user/id stays
= MOM_OWNER_ID, so the same user keeps backing agent registration.
"""
import os
import socket
import sys
import uuid
from pathlib import Path

NREPL_HOST = os.environ.get("MOM_NREPL_HOST", "localhost")
NREPL_PORT = int(os.environ.get("MOM_NREPL_PORT", "17888"))
ENV_FILE = Path(__file__).resolve().parent.parent / ".env.local"


def clj_str(s: str) -> str:
    """Render a Python string as a safely-escaped Clojure string literal."""
    escaped = s.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def load_env(path: Path) -> dict:
    out = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def bencode(obj) -> bytes:
    """Minimal bencode encoder for nREPL (dicts, lists, strings, ints)."""
    if isinstance(obj, dict):
        parts = [b"d"]
        for k in sorted(obj):
            parts.append(bencode(k))
            parts.append(bencode(obj[k]))
        parts.append(b"e")
        return b"".join(parts)
    if isinstance(obj, list):
        return b"l" + b"".join(bencode(x) for x in obj) + b"e"
    if isinstance(obj, int):
        return f"i{obj}e".encode()
    if isinstance(obj, str):
        b = obj.encode("utf-8")
        return f"{len(b)}:".encode() + b
    if isinstance(obj, bytes):
        return f"{len(obj)}:".encode() + obj
    raise TypeError(f"can't bencode {type(obj)}")


def bdecode(buf: bytes):
    """Minimal bencode decoder. Returns (value, rest)."""
    if buf[:1] == b"d":
        d, buf = {}, buf[1:]
        while buf[:1] != b"e":
            k, buf = bdecode(buf)
            v, buf = bdecode(buf)
            d[k.decode() if isinstance(k, bytes) else k] = v
        return d, buf[1:]
    if buf[:1] == b"l":
        lst, buf = [], buf[1:]
        while buf[:1] != b"e":
            v, buf = bdecode(buf)
            lst.append(v)
        return lst, buf[1:]
    if buf[:1] == b"i":
        end = buf.index(b"e")
        return int(buf[1:end]), buf[end + 1:]
    # string: <len>:<bytes>
    colon = buf.index(b":")
    n = int(buf[:colon])
    start = colon + 1
    return buf[start:start + n], buf[start + n:]


def nrepl_eval(code: str) -> dict:
    """Send a single eval op to nREPL and collect all response frames."""
    msg = bencode({"op": "eval", "code": code, "id": "seed-1"})
    with socket.create_connection((NREPL_HOST, NREPL_PORT), timeout=10) as s:
        s.sendall(msg)
        buf = b""
        frames = []
        s.settimeout(5)
        while True:
            try:
                chunk = s.recv(4096)
            except socket.timeout:
                break
            if not chunk:
                break
            buf += chunk
            while buf:
                try:
                    frame, rest = bdecode(buf)
                except (ValueError, IndexError):
                    break
                frames.append(frame)
                buf = rest
                if "done" in frame.get("status", []):
                    return {"frames": frames}
    return {"frames": frames}


def main() -> int:
    if not ENV_FILE.exists():
        print(f"!! {ENV_FILE} not found", file=sys.stderr)
        return 2
    env = load_env(ENV_FILE)
    owner_id = env.get("GM_OWNER_ID")
    if not owner_id:
        print("!! MOM_OWNER_ID missing from .env", file=sys.stderr)
        return 2
    try:
        uuid.UUID(owner_id)
    except ValueError:
        print(f"!! MOM_OWNER_ID is not a valid UUID: {owner_id}", file=sys.stderr)
        return 2

    # When MOM_DEV_EMAIL + MOM_DEV_PASSWORD are set, seed a login-capable user
    # (native "intergraph" provider): we hash the password in-JVM via
    # buddy.hashers/derive over nREPL and store it as :user/password-hash. The
    # :user/id stays = MOM_OWNER_ID so the same user both backs agent
    # registration AND can log in at the auth UI. Without them, fall back to the
    # original cosmetic dev user (no password, identity-type :local).
    dev_email = env.get("MOM_DEV_EMAIL")
    dev_password = env.get("MOM_DEV_PASSWORD")
    native = bool(dev_password)
    if native and not dev_email:
        print("!! MOM_DEV_PASSWORD is set but MOM_DEV_EMAIL is missing from .env", file=sys.stderr)
        print("   set both to seed a login-capable user, or neither for a cosmetic dev user", file=sys.stderr)
        return 2
    if native and len(dev_password) < 8:
        print("!! MOM_DEV_PASSWORD must be at least 8 characters (MoM rejects shorter passwords)", file=sys.stderr)
        return 2

    if native:
        require_form = ("(require '[mom.server.handlers :as h]\n"
                        "         '[mom.storage.protocol :as sp]\n"
                        "         '[buddy.hashers :as hashers])")
        user_map = ("{:user/id uid\n"
                    "             :user/name \"Local Dev\"\n"
                    f"             :user/email {clj_str(dev_email)}\n"
                    "             :user/identity-type :native\n"
                    "             :user/provider-id \"intergraph\"\n"
                    f"             :user/password-hash (hashers/derive {clj_str(dev_password)})\n"
                    "             :user/external-id \"local-dev\"\n"
                    "             :user/created-at now\n"
                    "             :user/updated-at now}")
    else:
        require_form = ("(require '[mom.server.handlers :as h]\n"
                        "         '[mom.storage.protocol :as sp])")
        user_map = ("{:user/id uid\n"
                    "             :user/name \"Local Dev\"\n"
                    "             :user/email \"dev@local\"\n"
                    "             :user/identity-type :local\n"
                    "             :user/provider-id \"local\"\n"
                    "             :user/external-id \"local-dev\"\n"
                    "             :user/created-at now\n"
                    "             :user/updated-at now}")

    # Send one top-level form. This nREPL endpoint completes an eval after the
    # first form, so a separate `require` followed by `let` only evaluated the
    # require and silently skipped the actual seed.
    code = f"""
(do
  {require_form}
  (let [uid #uuid "{owner_id}"
        now (java.util.Date.)
        user {user_map}]
    (sp/save! h/shared-storage :user user)
    (let [found (sp/find-by-id h/shared-storage :user uid)]
      (str "seeded user " (:user/id found) " <" (:user/email found) "> [" (name (:user/identity-type found)) "]"))))
"""

    label = f"{owner_id} <{dev_email}>" if native else owner_id
    print(f"-> seeding user {label} via nREPL at {NREPL_HOST}:{NREPL_PORT}")
    try:
        resp = nrepl_eval(code)
    except (ConnectionRefusedError, socket.timeout) as e:
        print(f"!! nREPL not reachable: {e}", file=sys.stderr)
        print("   is mom running and is MOM_NREPL_PORT exposed?", file=sys.stderr)
        return 1

    failed = False
    for f in resp["frames"]:
        if "value" in f:
            print(f"   {f['value'].decode() if isinstance(f['value'], bytes) else f['value']}")
        if "out" in f:
            print(f"   stdout: {f['out'].decode() if isinstance(f['out'], bytes) else f['out']}")
        if "err" in f:
            failed = True
            print(f"!! stderr: {f['err'].decode() if isinstance(f['err'], bytes) else f['err']}", file=sys.stderr)
        if "ex" in f or "root-ex" in f:
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
