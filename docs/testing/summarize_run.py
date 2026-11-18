"""Export time-ordered broadcast metrics from Android diagnostic ZIPs."""

import argparse
import csv
import io
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from zipfile import BadZipFile, ZipFile


METRICS = (
    "camera_fps", "encoded_fps", "published_fps", "video_budget_bps",
    "send_kbps", "rtt_ms", "video_queue_depth", "video_queue_media_age_ms",
    "encoder_pending", "video_queue_drops", "last_audio_age_ms",
    "process_cpu_pct", "java_heap_bytes", "native_heap_bytes", "thermal_status",
    "packets_sent", "packets_lost", "epoch",
)
EVENTS = {
    "broadcast_start_requested", "broadcast_advertised", "broadcast_cleaned_up",
    "default_network_changed", "relay_connected", "relay_video_resumed",
    "encoder_open", "media_recovery_attempt", "media_recovery_succeeded",
    "media_recovery_restarted", "media_recovery_failed", "media_recovery_exhausted",
}
NUMERIC = re.compile(r"-?[0-9]+(?:\.[0-9]+)?\Z")
FIELDS = re.compile(r"(?:^| )([a-z][a-z_0-9]*)=(\S+)")


def utc_timestamp(value):
    timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if timestamp.tzinfo is None:
        raise ValueError("Use a timestamp with an explicit offset, such as 2026-11-19T03:00:00Z")
    return timestamp.astimezone(timezone.utc)


def collect(paths, start=None, end=None):
    samples = {}
    events = set()
    for path in paths:
        with ZipFile(path) as archive:
            with io.TextIOWrapper(archive.open("events.log"), encoding="utf-8") as lines:
                for line in lines:
                    stamp, event, detail = line.rstrip("\n").split(" ", 2)
                    if event != "metrics" and event not in EVENTS:
                        continue
                    timestamp = utc_timestamp(stamp)
                    if (start is not None and timestamp < start) or (end is not None and timestamp > end):
                        continue
                    if event in EVENTS:
                        events.add((timestamp, event))
                        continue
                    fields = {
                        key: value for key, value in FIELDS.findall(detail)
                        if key in METRICS and NUMERIC.fullmatch(value)
                    }
                    samples[timestamp] = {"utc": stamp, **fields}
    return dict(sorted(samples.items())), Counter(event for _, event in events)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip", nargs="+", type=Path, help="Diagnostic exports from the same phone")
    parser.add_argument("--from-utc", type=utc_timestamp, help="Inclusive start of the run")
    parser.add_argument("--to-utc", type=utc_timestamp, help="Inclusive end of the run")
    parser.add_argument("--csv", type=Path, required=True, help="Path for the new metrics CSV")
    args = parser.parse_args()
    if args.from_utc and args.to_utc and args.from_utc > args.to_utc:
        parser.error("--from-utc must precede --to-utc")

    try:
        samples, events = collect(args.zip, args.from_utc, args.to_utc)
        if not samples:
            parser.error("No metric samples found in the selected time window")
        with args.csv.open("x", encoding="utf-8", newline="") as output:
            csv_writer = csv.DictWriter(output, fieldnames=("utc", *METRICS))
            csv_writer.writeheader()
            csv_writer.writerows(samples.values())
    except (OSError, ValueError, BadZipFile, KeyError) as error:
        parser.error(f"Could not process diagnostic ZIPs or write the CSV ({type(error).__name__})")

    timestamps = list(samples)
    print(f"Samples: {len(timestamps)}")
    print(f"UTC coverage: {timestamps[0].isoformat()} through {timestamps[-1].isoformat()}")
    if len(timestamps) > 1:
        gap = max((current - previous).total_seconds() for previous, current in zip(timestamps, timestamps[1:]))
        print(f"Largest gap: {gap:.1f} s")
    for key in ("video_queue_depth", "video_queue_media_age_ms", "process_cpu_pct",
                "java_heap_bytes", "native_heap_bytes", "thermal_status"):
        values = [float(sample[key]) for sample in samples.values() if key in sample]
        if values:
            print(f"{key}: first={values[0]:g}, last={values[-1]:g}, max={max(values):g}")
    if events:
        print("Events: " + ", ".join(f"{key}={value}" for key, value in sorted(events.items())))


if __name__ == "__main__":
    main()
