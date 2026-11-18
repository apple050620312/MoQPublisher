#!/usr/bin/env python3
"""Send synthetic audio and video over a loopback Draft 18 relay and decode both backup outputs."""

import argparse
from contextlib import ExitStack, contextmanager
import os
from pathlib import Path
import re
import runpy
import shutil
import socket
import subprocess
import time
from urllib.request import ProxyHandler, build_opener


REPO = Path(__file__).resolve().parents[2]
RELAY_VERSION = "moq-transport-18"
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


@contextmanager
def logged_process(command, log_path, *, env, stdin=None, stdout=None):
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            command, env=env, stdin=stdin, stdout=log if stdout is None else stdout,
            stderr=log, creationflags=CREATE_NO_WINDOW,
        )
        try:
            yield process
        finally:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=4)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def local_ports():
    with ExitStack() as reservations:
        relay_udp = reservations.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
        relay_udp.bind(("127.0.0.1", 0))
        rtmp_tcp = reservations.enter_context(socket.socket())
        rtmp_tcp.bind(("127.0.0.1", 0))
        srt_udp = reservations.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
        srt_udp.bind(("127.0.0.1", 0))
        return relay_udp.getsockname()[1], rtmp_tcp.getsockname()[1], srt_udp.getsockname()[1]


def wait_for_relay(address, relay):
    client = build_opener(ProxyHandler({}))
    deadline = time.monotonic() + 5
    while relay.poll() is None and time.monotonic() < deadline:
        try:
            with client.open(f"http://{address}/certificate.sha256", timeout=0.5) as response:
                if response.status == 200:
                    return
        except OSError:
            time.sleep(0.15)
    raise RuntimeError("Loopback relay did not start.")


def verify_case(args, encoder, broadcast, start_output):
    case = f"{encoder}-{'named' if broadcast else 'empty'}"
    relay_port, rtmp_port, srt_port = local_ports()
    relay_address = f"127.0.0.1:{relay_port}"
    endpoint = f"http://{relay_address}/anon"
    env = {name: value for name, value in os.environ.items() if not name.upper().startswith("MOQ_")}
    base = [args.moq, "--connect-version", RELAY_VERSION, "--broadcast", broadcast]
    with ExitStack() as workers:
        def launch(label, command, **kwargs):
            return workers.enter_context(logged_process(
                command, args.logs / f"{case}-{label}.log", env=env, **kwargs
            ))

        relay = launch("relay", [
            args.relay, "--listen", relay_address, "--listen-tls-generate", "127.0.0.1",
            "--web-http-listen", relay_address, "--auth-public", "anon/**",
        ])
        wait_for_relay(relay_address, relay)
        source = launch("source", [
            args.ffmpeg, "-v", "error", "-nostdin",
            "-re", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30",
            "-re", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000",
            "-t", "22", "-c:v", encoder, "-threads", "2", "-preset", "ultrafast",
            "-tune", "zerolatency", "-g", "30", "-c:a", "libopus",
            "-f", "mpegts", "-muxdelay", "0", "-muxpreload", "0", "-",
        ], stdout=subprocess.PIPE)
        publisher = launch("publish", [*base, "--connect", endpoint, "import", "ts"], stdin=source.stdout)
        source.stdout.close()
        time.sleep(2)
        if publisher.poll() is not None:
            raise RuntimeError(f"Publisher exited during {case}.")

        for protocol in ("rtmp", "srt"):
            output = start_output(
                protocol, base, dict(env, MOQ_CONNECT=endpoint), rtmp_port, srt_port, args.ffmpeg,
                unnamed=not broadcast,
            )
            workers.callback(output.close)
        time.sleep(3)
        readers = [
            ("RTMP", f"rtmp://127.0.0.1:{rtmp_port}/live/backup", ["-rtmp_enhanced_codecs", "hvc1,Opus"]),
            ("SRT", f"srt://127.0.0.1:{srt_port}?mode=caller", []),
        ]
        viewers = [
            (label, launch(label.lower(), [
                args.ffmpeg, "-v", "error", "-nostdin", *options, "-i", address,
                "-map", "0:v:0", "-map", "0:a:0", "-t", "2", "-progress", "pipe:1", "-f", "null", "-",
            ]))
            for label, address, options in readers
        ]
        for label, viewer in viewers:
            log_path = args.logs / f"{case}-{label.lower()}.log"
            status = viewer.wait(timeout=26)
            frames = re.findall(r"(?m)^frame=\s*(\d+)", log_path.read_text(encoding="utf-8", errors="replace"))
            if status or not frames or int(frames[-1]) < 60:
                raise RuntimeError(f"{case} {label} could not decode 60 frames with the audio track. See {log_path}.")
            print(f"{case} {label} decoded {frames[-1]} frames and audio.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay", default="moq-relay")
    parser.add_argument("--moq", default="moq")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--logs", type=Path, default=REPO / "build/gateway-regression")
    args = parser.parse_args()
    for command in ("relay", "moq", "ffmpeg"):
        executable = shutil.which(getattr(args, command))
        if not executable:
            parser.error(f"Install {getattr(args, command)} or pass the path with --{command}")
        setattr(args, command, executable)
    args.logs.mkdir(parents=True, exist_ok=True)
    start_output = runpy.run_path(str(REPO / "tools/moq-backup-gateway.py"))["start_output"]
    for encoder in ("libx264", "libx265"):
        for broadcast in ("validation.hang", ""):
            verify_case(args, encoder, broadcast, start_output)


if __name__ == "__main__":
    main()
