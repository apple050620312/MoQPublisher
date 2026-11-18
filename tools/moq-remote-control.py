#!/usr/bin/env python3
"""Control a MoQ Publisher session from a paired computer on the same network."""

import argparse
import base64
import getpass
import hashlib
import hmac
import ipaddress
import json
import re
import socket
import ssl
import sys
import time
from dataclasses import dataclass, field


COMMANDS = ("status", "mute", "unmute", "bitrate", "verify", "stop")
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(net) for net in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))


@dataclass(frozen=True)
class Pairing:
    host: str
    port: int
    fingerprint: str
    token: str = field(repr=False)

    @classmethod
    def from_code(cls, code):
        if not code.startswith("moq1.") or len(code) > 1_024:
            raise ValueError("Invalid pairing code.")
        body = code.removeprefix("moq1.")
        try:
            fields = json.loads(base64.b64decode(body + "=" * (-len(body) % 4), altchars=b"-_", validate=True))
            address = ipaddress.IPv4Address(fields["host"])
            port = fields["port"]
            identity = fields["sha256"]
            token = fields["token"]
            if (
                not any(address in network for network in PRIVATE_NETWORKS)
                or type(port) is not int
                or not 1_024 <= port <= 65_535
                or not re.fullmatch("[0-9a-f]{64}", identity)
                or not re.fullmatch("[a-zA-Z0-9_-]{43}", token)
            ):
                raise ValueError("Invalid pairing fields.")
            return cls(str(address), port, identity, token)
        except (ValueError, KeyError, TypeError) as error:
            raise ValueError("Invalid pairing code.") from error

    def send(self, command, bitrate=None):
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.check_hostname = False
        # The Android app generates a fresh certificate for each pairing.
        # Authenticate with the pin from the pairing code before sending credentials.
        tls.verify_mode = ssl.CERT_NONE
        with socket.create_connection((self.host, self.port), timeout=6) as connection:
            with tls.wrap_socket(connection, server_hostname=self.host) as server:
                fingerprint = hashlib.sha256(server.getpeercert(binary_form=True)).hexdigest()
                if not hmac.compare_digest(fingerprint, self.fingerprint):
                    raise ValueError("Android's TLS fingerprint does not match the pairing code.")
                request = {"token": self.token, "command": command}
                if bitrate is not None:
                    request["kbps"] = bitrate
                server.sendall(json.dumps(request, separators=(",", ":")).encode("utf-8") + b"\n")
                with server.makefile("rb") as reader:
                    line = reader.readline(16_385)
                if not line.endswith(b"\n") or len(line) > 16_384:
                    raise ValueError("Invalid response from the phone.")
                response = json.loads(line)
                if not isinstance(response, dict):
                    raise ValueError("Invalid response from the phone.")
                return response


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=COMMANDS, nargs="?", default="status")
    parser.add_argument("kbps", nargs="?", type=int, help="Target video bitrate in kilobits per second")
    parser.add_argument("--watch", action="store_true", help="Refresh status every two seconds")
    arguments = parser.parse_args()
    if (arguments.command == "bitrate") != (arguments.kbps is not None):
        parser.error("Supply kbps with the bitrate command.")
    if arguments.kbps is not None and not 100 <= arguments.kbps <= 50_000:
        parser.error("Bitrate must be between 100 and 50000 kbps.")
    if arguments.watch and arguments.command != "status":
        parser.error("--watch is available with status.")

    try:
        pairing = Pairing.from_code(getpass.getpass("MoQ Publisher pairing code: ").strip())
        while True:
            response = pairing.send(arguments.command, arguments.kbps)
            print(json.dumps(response, ensure_ascii=False, indent=2), flush=True)
            if not response.get("ok"):
                return 1
            if not arguments.watch:
                return 0
            time.sleep(2)
    except (OSError, ValueError, EOFError) as error:
        print(str(error), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
