"""Verified personal IMDb snapshot preparation; the manifest is the only activation point."""
from contextlib import contextmanager
from decimal import Decimal, InvalidOperation
from pathlib import Path
import csv
import datetime as dt
import gzip
import hashlib
import json
import os
import re
import shutil
import urllib.error
import urllib.request
import uuid

SOURCE = "https://datasets.imdbws.com/"
INPUTS = ("title.ratings.tsv.gz", "title.basics.tsv.gz")
ASSET = "imdb-discovery.tsv.bin"
HEADER = "imdbId\tcontentType\tprimaryTitle\toriginalTitle\tyear\tratingMilli\tvotes\tgenres"
NOTICE = ("Information courtesy of IMDb (https://www.imdb.com). Used with permission.\n\n"
          "IMDb non-commercial datasets for this private personal build. "
          "https://data.imdb.com/non-commercial-datasets/ . Only non-adult Movies/TV series "
          "with at least 1,000 votes are included. Provider scores are never used. "
          "Public/commercial distribution licensing remains separate.\n")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def properties(path):
    if not path.is_file():
        return {}
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#"))


def json_file(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def active_assets(root, props):
    if props.get("assetLayout") == "immutable-v1":
        digest = props.get("sha256", "")
        if not re.fullmatch("[0-9a-f]{64}", digest):
            raise ValueError("Invalid active snapshot digest")
        return root / "snapshots" / digest
    if props.get("assetLayout", "legacy") != "legacy":
        raise ValueError("Unknown snapshot layout")
    return root / "assets"


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_name(path.name + ".pending-" + uuid.uuid4().hex)
    try:
        with pending.open("xb") as dest:
            dest.write(data)
            dest.flush()
            os.fsync(dest.fileno())
        os.replace(pending, path)
    finally:
        pending.unlink(missing_ok=True)


@contextmanager
def refresh_lock(root):
    root.mkdir(parents=True, exist_ok=True)
    with (root / ".refresh.lock").open("a+b") as lock:
        if lock.tell() == 0:
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError("Another discovery refresh is already running") from error
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def cached_input(root, name, metadata):
    path = root / name
    expected = metadata.get(name, {})
    if (path.is_file() and expected.get("bytes") == path.stat().st_size
            and expected.get("sha256") == sha256(path)):
        return path, dict(expected)
    return None


def obtain_input(root, stage, name, metadata, reuse, opener, now):
    cached = cached_input(root, name, metadata)
    if reuse:
        if cached is None:
            raise ValueError(f"Cached {name} is missing or unverified; rerun without --reuse-downloads")
        return cached
    headers = {}
    if cached:
        for field, header in (("etag", "If-None-Match"), ("lastModified", "If-Modified-Since")):
            if cached[1].get(field):
                headers[header] = cached[1][field]
    request = urllib.request.Request(SOURCE + name, headers=headers)
    try:
        response = opener(request, timeout=30)
    except urllib.error.HTTPError as error:
        try:
            if error.code == 304 and cached:
                return cached
            raise
        finally:
            error.close()
    output = stage / name
    with response, output.open("xb") as dest:
        expected = response.headers.get("Content-Length")
        expected = int(expected) if expected is not None else None
        cap = 100 * 1024 * 1024 if name == INPUTS[0] else 1024 * 1024 * 1024
        size = 0
        while chunk := response.read(1024 * 1024):
            size += len(chunk)
            if size > cap:
                raise ValueError(f"Oversized source dataset: {name}")
            dest.write(chunk)
        if size == 0 or (expected is not None and size != expected):
            raise ValueError(f"Incomplete source dataset: {name}")
        dest.flush()
        os.fsync(dest.fileno())
        details = {"lastModified": response.headers.get("Last-Modified"),
                   "etag": response.headers.get("ETag"), "fetchedAt": now.isoformat(),
                   "bytes": size}
    details["sha256"] = sha256(output)
    return output, details


def dataset_rows(path, required):
    with gzip.open(path, "rt", encoding="utf-8", newline="") as source:
        rows = csv.DictReader(source, delimiter="\t", quoting=csv.QUOTE_NONE)
        if not required.issubset(rows.fieldnames or ()):
            raise ValueError(f"Unexpected source schema: {path.name}")
        for row in rows:
            if None in row or any(row.get(key) is None for key in required):
                raise ValueError(f"Malformed source row: {path.name}")
            yield row


def prepare_bundle(paths, stage, now, minimum_records):
    scores = {}
    for row in dataset_rows(paths[INPUTS[0]], {"tconst", "averageRating", "numVotes"}):
        try:
            raw = row["averageRating"]
            if len(raw) > 16:
                continue
            score, votes = Decimal(raw), int(row["numVotes"])
            if score.is_finite() and 0 < score <= 10 and 1000 <= votes <= 2_147_483_647:
                scores[row["tconst"]] = (int(score * 1000), votes)
        except (ValueError, InvalidOperation):
            continue
    bundle = stage / "bundle"
    bundle.mkdir()
    plain = stage / "snapshot.tsv"
    count = 0
    seen = set()
    required = {"tconst", "titleType", "primaryTitle", "originalTitle", "isAdult", "startYear", "genres"}
    with plain.open("w", encoding="utf-8", newline="") as dest:
        dest.write(HEADER + "\n")
        for row in dataset_rows(paths[INPUTS[1]], required):
            if (row["tconst"] not in scores or row["titleType"] not in
                    {"movie", "tvMovie", "tvSeries", "tvMiniSeries"} or row["isAdult"] != "0"):
                continue
            try:
                year = int(row["startYear"])
            except ValueError:
                continue
            if not 1900 <= year <= now.year:
                continue
            if not re.fullmatch(r"tt[0-9]{7,12}", row["tconst"]) or row["tconst"] in seen:
                raise ValueError("Invalid or duplicate title identity")
            seen.add(row["tconst"])
            score, votes = scores[row["tconst"]]
            kind = "movie" if row["titleType"] in {"movie", "tvMovie"} else "series"
            fields = [row["tconst"], kind, row["primaryTitle"], row["originalTitle"],
                      str(year), str(score), str(votes), row["genres"]]
            line = "\t".join(fields)
            if any("\t" in field or "\n" in field or "\r" in field for field in fields) or len(line) > 4096:
                raise ValueError("Invalid title text")
            dest.write(line + "\n")
            count += 1
            if count > 100_000:
                raise ValueError("Snapshot exceeds the app's supported record limit")
        dest.flush()
        os.fsync(dest.fileno())
    if count < minimum_records:
        raise ValueError("Unexpectedly small snapshot; keeping the active version")
    output = bundle / ASSET
    with output.open("xb") as compressed:
        with gzip.GzipFile(fileobj=compressed, mode="wb", mtime=0, filename="") as dest, plain.open("rb") as source:
            shutil.copyfileobj(source, dest)
        compressed.flush()
        os.fsync(compressed.fileno())
    # Re-read the complete compressed output (including CRC) before publishing its pointer.
    with gzip.open(output, "rt", encoding="utf-8") as source:
        if source.readline().rstrip("\n") != HEADER or sum(1 for _ in source) != count:
            raise ValueError("Prepared snapshot failed verification")
    return bundle, count, sha256(output)


def verify_bundle(bundle, digest, records):
    receipt = json_file(bundle / "imdb-discovery-receipt.json")
    index = bundle / ASSET
    if (not index.is_file() or sha256(index) != digest or receipt.get("snapshotSha256") != digest
            or receipt.get("records") != records or receipt.get("snapshotBytes") != index.stat().st_size
            or receipt.get("minimumVotes") != 1000
            or "Used with permission." not in (bundle / "IMDb-NOTICE.txt").read_text(encoding="utf-8")):
        raise ValueError("Existing immutable snapshot failed verification")
    return receipt


def refresh(project, reuse=False, opener=urllib.request.urlopen, minimum_records=50_000, now=None, progress=print):
    project = Path(project).resolve()
    root = project / ".local-discovery"
    manifest = project / "app/discovery-snapshot.properties"
    now = now or dt.datetime.now(dt.timezone.utc)
    with refresh_lock(root):
        previous = properties(manifest)
        prior_receipt = json_file(active_assets(root, previous) / "imdb-discovery-receipt.json")
        cache_receipt = json_file(root / "downloads-receipt.json") or prior_receipt
        stage = root / (".refresh-" + uuid.uuid4().hex)
        stage.mkdir()
        published = False
        warnings = []
        try:
            paths, inputs = {}, {}
            for name in INPUTS:
                progress(f"Checking {name}" if reuse else f"Fetching/checking {name}")
                paths[name], inputs[name] = obtain_input(root, stage, name, cache_receipt.get("inputs", {}), reuse, opener, now)
            progress("Preparing and verifying the independent snapshot")
            bundle, count, digest = prepare_bundle(paths, stage, now, minimum_records)
            if count < int(previous.get("records", "0")) * 0.8:
                raise ValueError("Unexpected loss of discovery coverage; active snapshot unchanged")
            unchanged = previous.get("sha256") == digest
            dataset = previous.get("dataset") if unchanged else "imdb-sha256-" + digest
            dataset = dataset or "imdb-sha256-" + digest
            receipt = {"source": SOURCE, "preparedAt": now.isoformat(), "mode": "cache-reuse" if reuse else "network-check",
                       "dataset": dataset, "records": count, "minimumVotes": 1000,
                       "snapshotBytes": (bundle / ASSET).stat().st_size, "snapshotSha256": digest, "inputs": inputs}
            atomic_write(bundle / "imdb-discovery-receipt.json", json.dumps(receipt, indent=2).encode())
            atomic_write(bundle / "IMDb-NOTICE.txt", NOTICE.encode())
            snapshots = root / "snapshots"
            snapshots.mkdir(exist_ok=True)
            target = snapshots / digest
            if not target.resolve().is_relative_to(root.resolve()) or not bundle.resolve().is_relative_to(stage.resolve()):
                raise ValueError("Snapshot path escaped the intended local directory")
            if target.exists():
                receipt = verify_bundle(target, digest, count)
                dataset = receipt.get("dataset", dataset)
            else:
                os.replace(bundle, target)
            values = {"assetLayout": "immutable-v1", "dataset": dataset, "sha256": digest,
                      "bytes": str(receipt["snapshotBytes"]), "records": str(count), "minimumVotes": "1000"}
            content = "# Personal-use IMDb snapshot; this file atomically selects a verified immutable bundle.\n"
            content += "".join(f"{key}={value}\n" for key, value in values.items())
            # Sole activation step. Old builds/files remain valid if this replace never happens.
            atomic_write(manifest, content.encode())
            published = True
            try:
                for name in INPUTS:
                    if paths[name] != root / name:
                        # The source is a newly downloaded file inside our checked staging directory.
                        assert paths[name].resolve().is_relative_to(stage.resolve())
                        os.replace(paths[name], root / name)
                atomic_write(root / "downloads-receipt.json", json.dumps({"inputs": inputs}, indent=2).encode())
            except OSError:
                warnings.append("Snapshot published; source-download caching was unavailable")
            return {"status": "unchanged" if unchanged else "updated", "dataset": dataset,
                    "records": count, "snapshotBytes": receipt["snapshotBytes"], "snapshotSha256": digest,
                    "mode": "cache-reuse" if reuse else "network-check", "checkedAt": now.isoformat(), "warnings": warnings}
        finally:
            # Only this invocation's uniquely created staging directory may be removed recursively.
            if stage.resolve().parent != root.resolve() or not stage.name.startswith(".refresh-"):
                raise ValueError("Refusing to clean a path outside refresh staging")
            try:
                shutil.rmtree(stage)
            except OSError:
                if published:
                    warnings.append("Published snapshot is valid; temporary staging cleanup is pending")
