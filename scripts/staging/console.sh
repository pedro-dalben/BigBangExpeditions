#!/usr/bin/env bash
# console.sh — send a command to the staging server via RCON.
# Usage: console.sh "command"
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

CMD="${1:?usage: console.sh <command>}"

python3 - "$SERVER_DIR" "$CMD" <<'PYEOF'
import socket, struct, sys, os

server_dir, cmd = sys.argv[1], sys.argv[2]
props = {}
with open(os.path.join(server_dir, "server.properties")) as f:
    for line in f:
        if "=" in line and not line.startswith("#"):
            k, v = line.strip().split("=", 1)
            props[k] = v

host = "127.0.0.1"
port = int(props.get("rcon.port", "25575"))
pw = props.get("rcon.password", "")

def pkt(rid, ptype, body):
    data = struct.pack("<ii", rid, ptype) + body.encode("utf8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data

def read_pkt(s):
    ln = struct.unpack("<i", s.recv(4))[0]
    data = b""
    while len(data) < ln:
        data += s.recv(ln - len(data))
    rid, ptype = struct.unpack("<ii", data[:8])
    return rid, ptype, data[8:-2].decode("utf8", "replace")

s = socket.create_connection((host, port), timeout=10)
s.sendall(pkt(1, 3, pw))
rid, _, _ = read_pkt(s)
if rid == -1:
    print("RCON AUTH FAILED", file=sys.stderr); sys.exit(1)
s.sendall(pkt(2, 2, cmd))
_, ptype, body = read_pkt(s)
print(body)
s.close()
PYEOF
