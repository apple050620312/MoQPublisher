"""Serve a MoQ broadcast to RTMP and SRT readers on the same computer."""

import argparse
from dataclasses import dataclass
from getpass import getpass
import os
import shutil
import subprocess
import time
from urllib.parse import urlsplit


@dataclass
class Output:
    label: str
    processes: list[subprocess.Popen]

    def stopped(self):
        return any(process.poll() is not None for process in self.processes)

    def close(self):
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
        for process in self.processes:
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def start_output(protocol, common, env, rtmp_port, srt_port, ffmpeg, *, unnamed=False):
    subprocess_options = {
        "stderr": subprocess.DEVNULL,
        "creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0),
    }
    export = [*common, "export", "--catalog-format", "hang"]
    rtmp = ["rtmp", "--listen", f"127.0.0.1:{rtmp_port}"]
    if protocol == "rtmp" and not unnamed:
        command = [*export, *rtmp]
        process = subprocess.Popen(command, env=env, stdout=subprocess.DEVNULL, **subprocess_options)
        return Output("RTMP", [process])

    local_env = {name: value for name, value in env.items() if not name.upper().startswith("MOQ_")}
    if protocol == "rtmp":
        # The CLI's RTMP listener needs a name. Remux into a local MoQ origin
        # after subscribing to the empty name on the real relay.
        container = "flv"
        command = [common[0], "--log-level", "error", "--listen-tcp-bind", "127.0.0.1:0",
                   "--auth-public", "backup.hang/**", "--broadcast", "backup.hang",
                   "import", "flv", "--", "export", "--catalog-format", "hang", *rtmp]
    else:
        container = "ts"
        command = [
            ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-analyzeduration", "1000000", "-probesize", "1048576",
            "-f", "mpegts", "-i", "pipe:0", "-map", "0:v?", "-map", "0:a?", "-c", "copy",
            "-f", "mpegts", f"srt://127.0.0.1:{srt_port}?mode=listener",
        ]

    source = subprocess.Popen([*export, container], env=env, stdout=subprocess.PIPE, **subprocess_options)
    try:
        sink = subprocess.Popen(command, env=local_env, stdin=source.stdout, stdout=subprocess.DEVNULL, **subprocess_options)
    except BaseException:
        Output(protocol.upper(), [source]).close()
        raise
    finally:
        source.stdout.close()
    return Output(protocol.upper(), [source, sink])


def port(value):
    number = int(value)
    if not 1 <= number <= 65_535:
        raise argparse.ArgumentTypeError("Port must be between 1 and 65535")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--broadcast", required=True, help="The subscription namespace. Pass --broadcast= for an empty name.")
    parser.add_argument("--protocol", choices=("both", "rtmp", "srt"), default="both")
    parser.add_argument("--rtmp-port", type=port, default=1935)
    parser.add_argument("--srt-port", type=port, default=9000)
    parser.add_argument("--version", help="Optional MoQ version, such as moq-transport-18 for Cloudflare")
    parser.add_argument("--moq", default="moq", help="Path to the upstream MoQ CLI")
    parser.add_argument("--ffmpeg", default="ffmpeg", help="Path to FFmpeg with libsrt support")
    args = parser.parse_args()
    moq = shutil.which(args.moq)
    ffmpeg = shutil.which(args.ffmpeg)
    if not moq:
        parser.error("Install the MoQ CLI or pass its path with --moq")
    if args.protocol != "rtmp" and not ffmpeg:
        parser.error("SRT requires FFmpeg with libsrt support")
    relay_url = os.environ.get("MOQ_CONNECT") or getpass("Subscribe-only relay endpoint: ").strip()
    try:
        relay = urlsplit(relay_url)
        supported = relay.scheme in {"https", "wss", "moqt", "moql"} or (
            relay.scheme in {"http", "ws", "tcp"} and relay.hostname in {"localhost", "127.0.0.1", "::1"}
        )
    except ValueError:
        supported = False
    if not supported or not relay.hostname or relay.username is not None:
        parser.error("Enter a supported Subscribe endpoint with a valid host")

    env = os.environ.copy()
    env["MOQ_CONNECT"] = relay_url
    common = [moq, "--log-level", "error", "--broadcast", args.broadcast]
    if args.version:
        common += ["--connect-version", args.version]
    protocols = ("rtmp", "srt") if args.protocol == "both" else (args.protocol,)
    outputs = {}
    retry_after = dict.fromkeys(protocols, 0.0)
    failures = dict.fromkeys(protocols, 0)
    if "rtmp" in protocols:
        print(f"RTMP reader: rtmp://127.0.0.1:{args.rtmp_port}/live/backup", flush=True)
    if "srt" in protocols:
        print(f"SRT reader: srt://127.0.0.1:{args.srt_port}?mode=caller", flush=True)
    try:
        while True:
            now = time.monotonic()
            for protocol in protocols:
                output = outputs.get(protocol)
                if output and output.stopped():
                    output.close()
                    del outputs[protocol]
                    retry_after[protocol] = now + min(2 ** failures[protocol], 30)
                    failures[protocol] = min(failures[protocol] + 1, 5)
                    print(f"{output.label} disconnected. Reconnecting.", flush=True)
                elif output is None and now >= retry_after[protocol]:
                    try:
                        outputs[protocol] = start_output(
                            protocol, common, env, args.rtmp_port, args.srt_port, ffmpeg, unnamed=not args.broadcast
                        )
                    except OSError:
                        retry_after[protocol] = now + 5
                        print(f"Could not start the {protocol.upper()} worker. Retrying.", flush=True)
            time.sleep(0.25)
    except KeyboardInterrupt:
        return 0
    finally:
        for output in outputs.values():
            output.close()


if __name__ == "__main__":
    raise SystemExit(main())
