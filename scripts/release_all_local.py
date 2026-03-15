#!/usr/bin/env python3
"""Local publisher for all Paradigm jars to Modrinth + CurseForge.

- Reads version from gradle.properties (mod_version) unless overridden.
- Uses .github/release-matrix.json entries with __VERSION__ placeholder.
- Uploads each artifact to Modrinth and CurseForge.

Required secrets (env or --secrets-file):
  MODRINTH_TOKEN
  MODRINTH_PROJECT_ID
  CURSEFORGE_TOKEN
  CURSEFORGE_PROJECT_ID
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import pathlib
import subprocess
import sys
import time
import uuid
import urllib.error
import urllib.parse
import urllib.request
from typing import Dict, List, Optional, Tuple

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_MATRIX = ROOT / ".github" / "release-matrix.json"
DEFAULT_RELEASE_DIR = ROOT / "release-jars"
DEFAULT_CHANGELOG = ROOT / "CHANGELOG.md"
DEFAULT_GRADLE_PROPS = ROOT / "gradle.properties"
DEFAULT_SECRETS_FILE = ROOT / "scripts" / "release-secrets.local.env"
DEFAULT_CURSEFORGE_API_BASE = "https://minecraft.curseforge.com"
DEFAULT_STATE_FILE = ROOT / "scripts" / ".release-state.json"

LOADER_LABELS = {
    "fabric": "Fabric",
    "forge": "Forge",
    "neoforge": "NeoForge",
}


def die(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(1)


def load_state(path: pathlib.Path) -> Dict[str, bool]:
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def save_state(path: pathlib.Path, state: Dict[str, bool]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=True, indent=2), encoding="utf-8")


def state_key(target: str, version: str, release_type: str, artifact_name: str) -> str:
    return f"{target}:{version}:{release_type}:{artifact_name}"


def read_env_file(path: pathlib.Path) -> Dict[str, str]:
    values: Dict[str, str] = {}
    if not path.exists():
        die(f"Secrets file not found: {path}")
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        values[k.strip()] = v.strip().strip('"').strip("'")
    return values


def get_mod_version(gradle_props: pathlib.Path) -> str:
    for raw in gradle_props.read_text(encoding="utf-8").splitlines():
        if raw.startswith("mod_version="):
            return raw.split("=", 1)[1].strip()
    die(f"mod_version not found in {gradle_props}")


def load_matrix(matrix_file: pathlib.Path, version: str) -> List[dict]:
    data = json.loads(matrix_file.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        die("release-matrix.json must be a JSON array")
    out: List[dict] = []
    for item in data:
        if not isinstance(item, dict):
            die("Every matrix item must be an object")
        clone = dict(item)
        artifact = str(clone.get("artifact", ""))
        if not artifact:
            die("Matrix item is missing 'artifact'")
        clone["artifact"] = artifact.replace("__VERSION__", version)
        out.append(clone)
    return out


def build_release_jars(version: str) -> None:
    cmd = ["./gradlew", f"-Pmod_version={version}", "clean", "build", "collectOutputs"]
    print(f"==> Building all jars with mod_version={version}")
    subprocess.run(cmd, cwd=ROOT, check=True)


def verify_files(matrix: List[dict], release_dir: pathlib.Path) -> None:
    missing: List[str] = []
    for item in matrix:
        p = release_dir / item["artifact"]
        if not p.exists():
            missing.append(str(p))
    if missing:
        die("Missing release jars:\n" + "\n".join(missing))


def multipart_body(fields: Dict[str, str], file_field: str, file_name: str, file_bytes: bytes) -> Tuple[bytes, str]:
    boundary = f"----ParadigmBoundary{uuid.uuid4().hex}"
    chunks: List[bytes] = []

    for k, v in fields.items():
        chunks.extend([
            f"--{boundary}\r\n".encode("utf-8"),
            f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode("utf-8"),
            v.encode("utf-8"),
            b"\r\n",
        ])

    mime = mimetypes.guess_type(file_name)[0] or "application/octet-stream"
    chunks.extend([
        f"--{boundary}\r\n".encode("utf-8"),
        f'Content-Disposition: form-data; name="{file_field}"; filename="{file_name}"\r\n'.encode("utf-8"),
        f"Content-Type: {mime}\r\n\r\n".encode("utf-8"),
        file_bytes,
        b"\r\n",
        f"--{boundary}--\r\n".encode("utf-8"),
    ])

    return b"".join(chunks), boundary


def http_json(method: str, url: str, headers: Dict[str, str], payload: Optional[dict] = None) -> dict:
    data = None
    req_headers = dict(headers)
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        req_headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data, method=method, headers=req_headers)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        die(f"HTTP {e.code} {url}\n{body}")


def http_multipart(method: str, url: str, headers: Dict[str, str], fields: Dict[str, str], file_field: str, file_path: pathlib.Path) -> dict:
    file_bytes = file_path.read_bytes()
    body, boundary = multipart_body(fields, file_field, file_path.name, file_bytes)
    req_headers = dict(headers)
    req_headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"

    req = urllib.request.Request(url, data=body, method=method, headers=req_headers)
    try:
        with urllib.request.urlopen(req) as resp:
            text = resp.read().decode("utf-8")
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")
        die(f"HTTP {e.code} {url}\n{body_text}")


def safe_http_json(method: str, url: str, headers: Dict[str, str]) -> Optional[object]:
    """Best-effort JSON request for existence checks. Returns None on HTTP errors."""
    req = urllib.request.Request(url, method=method, headers=dict(headers))
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        print(f"WARN: existence check failed ({e.code}) for {url}: {body}")
        return None
    except Exception as e:
        print(f"WARN: existence check failed for {url}: {e}")
        return None


def modrinth_version_exists(token: str, project_id: str, loader: str, mc_version: str, version_number: str) -> bool:
    params = urllib.parse.urlencode({
        "loaders": json.dumps([loader]),
        "game_versions": json.dumps([mc_version]),
    })
    url = f"https://api.modrinth.com/v2/project/{project_id}/version?{params}"
    payload = safe_http_json(
        "GET",
        url,
        {
            "Authorization": token,
            "Accept": "application/json",
            "User-Agent": "Paradigm-Local-Release-Script/1.0",
        },
    )
    if payload is None:
        return False

    versions = payload if isinstance(payload, list) else payload.get("data", []) if isinstance(payload, dict) else []
    target = version_number.strip().lower()
    for item in versions:
        if str(item.get("version_number", "")).strip().lower() == target:
            return True
    return False


def curseforge_file_exists(token: str, api_base: str, project_id: str, display_name: str, artifact_name: str) -> bool:
    url = f"{api_base}/api/projects/{project_id}/files"
    payload = safe_http_json(
        "GET",
        url,
        {
            "X-Api-Token": token,
            "Accept": "application/json",
            "User-Agent": "Paradigm-Local-Release-Script/1.0",
        },
    )
    if payload is None:
        return False

    files = payload if isinstance(payload, list) else payload.get("data", []) if isinstance(payload, dict) else []
    wanted_display = display_name.strip().lower()
    wanted_file = artifact_name.strip().lower()
    for item in files:
        if str(item.get("displayName", "")).strip().lower() == wanted_display:
            return True
        if str(item.get("fileName", "")).strip().lower() == wanted_file:
            return True
    return False


def resolve_curseforge_game_versions(cf_token: str, minecraft_version: str, loader: str,
                                     cache: dict, extra_labels: Optional[List[str]] = None) -> List[int]:
    if "versions" not in cache:
        try:
            raw = http_json(
                "GET",
                f"{cache['cf_api_base']}/api/game/versions",
                {"X-Api-Token": cf_token, "Accept": "application/json"},
            )
            cache["versions"] = raw.get("data", raw) if isinstance(raw, dict) else raw
        except SystemExit:
            die(
                "CurseForge API access denied (403). Check CURSEFORGE_TOKEN: it must be a valid CurseForge API token "
                "for the account that can upload to this project."
            )

    versions = cache["versions"]
    if not isinstance(versions, list):
        die(
            f"Unexpected CurseForge versions payload from {cache['cf_api_base']}/api/game/versions: "
            f"{type(versions).__name__}"
        )

    def normalize(value: str) -> str:
        return value.strip().lower().replace(" ", "").replace(".", "").replace("-", "")

    def version_tokens(v: dict) -> tuple[str, str]:
        version_name = str(v.get("name", "")).strip().lower()
        version_slug = str(v.get("slug", "")).strip().lower()
        return version_name, version_slug

    def find_exact(name: str) -> Optional[int]:
        wanted = name.strip().lower()
        for v in versions:
            version_name, version_slug = version_tokens(v)
            if version_name == wanted or version_slug == wanted:
                return int(v["id"])
        return None

    def find_fuzzy(name: str) -> Optional[int]:
        wanted = normalize(name)
        for v in versions:
            version_name = normalize(str(v.get("name", "")))
            version_slug = normalize(str(v.get("slug", "")))
            if version_name == wanted or version_slug == wanted:
                return int(v["id"])
        return None

    def find_contains(name: str) -> Optional[int]:
        wanted = normalize(name)
        if not wanted:
            return None
        # Prefer exact-ish shorter match first, otherwise fall back to first contains hit.
        candidates: List[tuple[int, int]] = []
        for v in versions:
            version_name = normalize(str(v.get("name", "")))
            version_slug = normalize(str(v.get("slug", "")))
            if wanted in version_name or wanted in version_slug:
                score = min(len(version_name), len(version_slug))
                candidates.append((int(v["id"]), score))
        if not candidates:
            return None
        candidates.sort(key=lambda x: x[1])
        return candidates[0][0]

    def sample_candidates(name: str, limit: int = 8) -> List[str]:
        wanted = normalize(name)
        out: List[str] = []
        for v in versions:
            vid = str(v.get("id", "?"))
            version_name, version_slug = version_tokens(v)
            n_name = normalize(version_name)
            n_slug = normalize(version_slug)
            if wanted and (wanted in n_name or wanted in n_slug):
                out.append(f"{vid}:{version_name} ({version_slug})")
                if len(out) >= limit:
                    break
        return out

    ids: List[int] = []

    mc_id = find_exact(minecraft_version)
    if mc_id is None:
        mc_id = find_fuzzy(minecraft_version)
    if mc_id is None:
        mc_id = find_contains(minecraft_version)
    if mc_id is not None:
        ids.append(mc_id)

    loader_label = LOADER_LABELS.get(loader.lower(), loader)
    loader_id = find_exact(loader_label)
    if loader_id is None:
        loader_id = find_fuzzy(loader_label)
    if loader_id is None:
        loader_id = find_contains(loader_label)
    if loader_id is not None and loader_id not in ids:
        ids.append(loader_id)

    extras = extra_labels or []
    for label in extras:
        x_id = find_exact(label)
        if x_id is None:
            x_id = find_fuzzy(label)
        if x_id is None:
            x_id = find_contains(label)
        if x_id is None:
            print(f"WARN: CurseForge tag not found: '{label}'")
            continue
        if x_id not in ids:
            ids.append(x_id)

    if not ids:
        mc_matches = ", ".join(sample_candidates(minecraft_version)) or "none"
        loader_matches = ", ".join(sample_candidates(loader_label)) or "none"
        die(
            f"CurseForge game version IDs not resolved for minecraft={minecraft_version}, loader={loader}. "
            f"base={cache['cf_api_base']} totalVersions={len(versions)} "
            f"Nearby minecraft matches: [{mc_matches}] | loader matches: [{loader_matches}]"
        )

    return ids


def publish_modrinth(token: str, project_id: str, artifact: pathlib.Path, loader: str, mc_version: str,
                     release_type: str, version: str, changelog: str, dry_run: bool,
                     skip_existing: bool) -> bool:
    file_part_name = artifact.name
    version_number = f"{version}-{loader}-{mc_version}"

    if not dry_run and skip_existing and modrinth_version_exists(token, project_id, loader, mc_version, version_number):
        print(f"[SKIP] Modrinth version already exists: {version_number}")
        return True

    data = {
        "project_id": project_id,
        "name": f"Paradigm {version} ({loader} {mc_version})",
        "version_number": version_number,
        "version_type": release_type,
        "loaders": [loader],
        "game_versions": [mc_version],
        "changelog": changelog,
        "dependencies": [],
        "file_parts": [file_part_name],
        "featured": False,
    }

    if dry_run:
        print(f"[DRY] Modrinth -> {artifact.name} ({loader} {mc_version})")
        return False

    http_multipart(
        "POST",
        "https://api.modrinth.com/v2/version",
        {
            "Authorization": token,
            "Accept": "application/json",
            "User-Agent": "Paradigm-Local-Release-Script/1.0",
        },
        {"data": json.dumps(data)},
        file_part_name,
        artifact,
    )
    print(f"[OK] Modrinth uploaded: {artifact.name}")
    return True


def publish_curseforge(token: str, project_id: str, artifact: pathlib.Path, loader: str, mc_version: str,
                       release_type: str, version: str, changelog: str, dry_run: bool,
                       cf_cache: dict, skip_existing: bool,
                       extra_labels: Optional[List[str]] = None) -> bool:
    if dry_run:
        print(f"[DRY] CurseForge -> {artifact.name} ({loader} {mc_version})")
        return False

    display_name = f"Paradigm {version} ({loader} {mc_version})"
    if skip_existing and curseforge_file_exists(token, cf_cache["cf_api_base"], project_id, display_name, artifact.name):
        print(f"[SKIP] CurseForge file already exists: {display_name}")
        return True

    game_versions = resolve_curseforge_game_versions(token, mc_version, loader, cf_cache, extra_labels)
    metadata = {
        "displayName": display_name,
        "changelog": changelog,
        "changelogType": "markdown",
        "releaseType": release_type,
        "gameVersions": game_versions,
    }

    http_multipart(
        "POST",
        f"{cf_cache['cf_api_base']}/api/projects/{project_id}/upload-file",
        {
            "X-Api-Token": token,
            "Accept": "application/json",
            "User-Agent": "Paradigm-Local-Release-Script/1.0",
        },
        {"metadata": json.dumps(metadata)},
        "file",
        artifact,
    )
    print(f"[OK] CurseForge uploaded: {artifact.name}")
    return True


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish all Paradigm jars locally")
    parser.add_argument("--version", help="Override version (defaults to gradle.properties mod_version)")
    parser.add_argument("--release-type", default="beta", choices=["release", "beta", "alpha"])
    parser.add_argument("--matrix", type=pathlib.Path, default=DEFAULT_MATRIX)
    parser.add_argument("--release-dir", type=pathlib.Path, default=DEFAULT_RELEASE_DIR)
    parser.add_argument("--changelog-file", type=pathlib.Path, default=DEFAULT_CHANGELOG)
    parser.add_argument("--secrets-file", type=pathlib.Path, help="Local env file with tokens/ids")
    parser.add_argument("--only", choices=["all", "modrinth", "curseforge"], default="all")
    parser.add_argument("--build", action="store_true", help="Run ./gradlew build collectOutputs first")
    parser.add_argument("--dry-run", action="store_true", help="Validate plan without uploading")
    parser.add_argument("--no-skip-existing", action="store_true",
                        help="Always upload; do not skip when same version/file already exists")
    parser.add_argument("--state-file", type=pathlib.Path, default=DEFAULT_STATE_FILE,
                        help="Local checkpoint file for already successful uploads")
    parser.add_argument("--curseforge-api-base", default=DEFAULT_CURSEFORGE_API_BASE,
                        help="Base URL for CurseForge API (default: https://minecraft.curseforge.com)")
    args = parser.parse_args()

    skip_existing = not args.no_skip_existing

    version = args.version or get_mod_version(DEFAULT_GRADLE_PROPS)
    changelog = args.changelog_file.read_text(encoding="utf-8")

    secrets: Dict[str, str] = dict(os.environ)
    secrets_file = args.secrets_file
    if secrets_file is None and DEFAULT_SECRETS_FILE.exists():
        secrets_file = DEFAULT_SECRETS_FILE

    if secrets_file:
        secrets.update(read_env_file(secrets_file))
        print(f"==> Loaded secrets from: {secrets_file}")

    required = []
    if args.only in ("all", "modrinth"):
        required.extend(["MODRINTH_TOKEN", "MODRINTH_PROJECT_ID"])
    if args.only in ("all", "curseforge"):
        required.extend(["CURSEFORGE_TOKEN", "CURSEFORGE_PROJECT_ID"])

    if not args.dry_run:
        missing = [k for k in required if not secrets.get(k)]
        if missing:
            die("Missing secrets: " + ", ".join(missing))

    matrix = load_matrix(args.matrix, version)

    if args.build:
        build_release_jars(version)

    verify_files(matrix, args.release_dir)

    print(f"==> Version: {version}")
    print(f"==> Release type: {args.release_type}")
    print(f"==> Artifacts: {len(matrix)}")
    print(f"==> Skip existing: {skip_existing}")

    cf_cache: dict = {}
    cf_cache["cf_api_base"] = args.curseforge_api_base.rstrip("/")
    state = load_state(args.state_file)
    failures: List[str] = []

    for item in matrix:
        artifact = args.release_dir / item["artifact"]
        loader = str(item["loader"])
        mc = str(item["minecraft"])
        extra_cf_tags = [str(x) for x in item.get("curseforge_tags", []) if str(x).strip()]
        print(f"\n-- {artifact.name} | {loader} {mc}")

        if args.only in ("all", "modrinth"):
            m_key = state_key("modrinth", version, args.release_type, artifact.name)
            if not args.dry_run and skip_existing and state.get(m_key):
                print(f"[SKIP] Modrinth already marked done in state: {artifact.name}")
            else:
                try:
                    done = publish_modrinth(
                        token=secrets.get("MODRINTH_TOKEN", ""),
                        project_id=secrets.get("MODRINTH_PROJECT_ID", ""),
                        artifact=artifact,
                        loader=loader,
                        mc_version=mc,
                        release_type=args.release_type,
                        version=version,
                        changelog=changelog,
                        dry_run=args.dry_run,
                        skip_existing=skip_existing,
                    )
                    if done and not args.dry_run:
                        state[m_key] = True
                        save_state(args.state_file, state)
                except SystemExit as e:
                    failures.append(f"modrinth:{artifact.name}: {e}")
                    print(f"[FAIL] Modrinth {artifact.name}")
            time.sleep(0.3)

        if args.only in ("all", "curseforge"):
            c_key = state_key("curseforge", version, args.release_type, artifact.name)
            if not args.dry_run and skip_existing and state.get(c_key):
                print(f"[SKIP] CurseForge already marked done in state: {artifact.name}")
            else:
                try:
                    done = publish_curseforge(
                        token=secrets.get("CURSEFORGE_TOKEN", ""),
                        project_id=secrets.get("CURSEFORGE_PROJECT_ID", ""),
                        artifact=artifact,
                        loader=loader,
                        mc_version=mc,
                        release_type=args.release_type,
                        version=version,
                        changelog=changelog,
                        dry_run=args.dry_run,
                        cf_cache=cf_cache,
                        skip_existing=skip_existing,
                        extra_labels=extra_cf_tags,
                    )
                    if done and not args.dry_run:
                        state[c_key] = True
                        save_state(args.state_file, state)
                except SystemExit as e:
                    failures.append(f"curseforge:{artifact.name}: {e}")
                    print(f"[FAIL] CurseForge {artifact.name}")
            time.sleep(0.3)

    if failures:
        print("\nCompleted with failures:")
        for f in failures:
            print(f" - {f}")
        die(f"{len(failures)} upload(s) failed")

    print("\nDone.")


if __name__ == "__main__":
    main()


