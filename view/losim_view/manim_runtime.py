"""Where manim actually runs.

manim is heavy — cairo, pango, a compiler, a few hundred megabytes — and a machine
that runs labs should not have to carry it. So it is never imported into the
process that decides what to draw. It runs in a **sidecar**, and the only thing
that crosses the boundary is one Frame as JSON.

Three sidecars, tried in this order:

  in-process   manim is importable right here — nothing to isolate
  venv         a losim-managed virtualenv under build/.manim-venv
  docker       the official manimcommunity/manim image

The picture is identical in all three, because all three run the same
render_job on the same Frame. Which one is in use is reported, never guessed at
by the caller.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import sysconfig
from dataclasses import dataclass
from importlib.util import find_spec
from pathlib import Path
from typing import Callable

IMAGE = "manimcommunity/manim:stable"
VENV_DIR = Path("build") / ".manim-venv"

Log = Callable[[str], None]


def _noop(_line: str) -> None:
    pass


@dataclass(frozen=True)
class Runtime:
    kind: str                 # inprocess | venv | docker
    detail: str               # what to tell a human
    root: Path                # the losim checkout; docker mounts it

    # ------------------------------------------------------------------ run

    def render(self, frame_json: Path, scene: str, out_dir: Path, *,
               quality: str = "l", name: str = "scene", log: Log = _noop) -> Path:
        """Frame in, video out. Raises RuntimeError with the log tail on failure."""
        out_dir.mkdir(parents=True, exist_ok=True)
        mounts = self.mounts(out_dir)
        argv, env = self._command(frame_json, scene, out_dir, quality, name)
        log(f"$ {' '.join(argv)}")
        video: Path | None = None
        tail: list[str] = []
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, bufsize=1, env=env, cwd=self.root)
        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.rstrip("\n")
            if line.startswith("VIDEO: "):
                video = self._from_container(line[len("VIDEO: "):], mounts)
                continue
            tail = (tail + [line])[-40:]
            log(line)
        if proc.wait() != 0 or video is None:
            raise RuntimeError("manim failed:\n" + "\n".join(tail[-12:]))
        return video

    def mounts(self, out_dir: Path) -> list[tuple[Path, str]]:
        """What the container may see: the framework's code, and where to write.

        Two mounts rather than one, because a lab lives in the student's own
        repository while losim lives in .losim/ — the video belongs next to the
        run that produced it, not inside the framework.
        """
        return [(Path(out_dir).resolve(), "/out"), (self.root, "/work")]

    def _command(self, frame_json: Path, scene: str, out_dir: Path,
                 quality: str, name: str) -> tuple[list[str], dict]:
        mounts = self.mounts(out_dir)
        job = ["-m", "losim_view.render_job", self._to_container(frame_json, mounts),
               "--scene", scene, "--quality", quality, "--name", name,
               "--out", self._to_container(out_dir, mounts)]
        env = dict(os.environ)
        if self.kind == "docker":
            args = ["docker", "run", "--rm", "-w", "/work",
                    "-e", "PYTHONPATH=/work/view", "-e", "HOME=/tmp"]
            for host, inside in mounts:
                args += ["-v", f"{host}:{inside}" + (":ro" if inside == "/work" else "")]
            # Without this the container writes root-owned files into the
            # student's checkout on Linux, which they then cannot delete.
            if os.name == "posix" and sys.platform != "darwin":
                args += ["--user", f"{os.getuid()}:{os.getgid()}"]
            return args + [IMAGE, "python3"] + job, env
        env["PYTHONPATH"] = os.pathsep.join(
            [str(self.root / "view")] + ([env["PYTHONPATH"]] if env.get("PYTHONPATH") else []))
        return [self._python()] + job, env

    def _python(self) -> str:
        if self.kind == "venv":
            return str(_venv_python(self.root))
        return sys.executable

    def _to_container(self, p: Path | str, mounts: list[tuple[Path, str]] | None = None) -> str:
        p = Path(p).resolve()
        if self.kind != "docker":
            return str(p)
        for host, inside in (mounts or self.mounts(p)):
            if p == host:
                return inside
            if p.is_relative_to(host):
                return f"{inside}/{p.relative_to(host)}"
        raise RuntimeError(
            f"{p} is not inside anything the sidecar can see ("
            + ", ".join(str(h) for h, _ in (mounts or [])) + ")")

    def _from_container(self, p: str, mounts: list[tuple[Path, str]] | None = None) -> Path:
        if self.kind != "docker":
            return Path(p)
        for host, inside in (mounts or [(self.root, "/work")]):
            if p.startswith(inside + "/"):
                return host / p[len(inside) + 1:]
        return Path(p)


# ------------------------------------------------------------------ discovery

def _venv_python(root: Path) -> Path:
    base = root / VENV_DIR
    return base / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def _has_manim(python: Path | str) -> bool:
    try:
        r = subprocess.run([str(python), "-c", "import manim"], capture_output=True, timeout=120)
        return r.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def _docker_ready() -> bool:
    if not shutil.which("docker"):
        return False
    try:
        r = subprocess.run(["docker", "image", "inspect", IMAGE],
                           capture_output=True, timeout=60)
        return r.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def discover(root: Path) -> list[Runtime]:
    """Every sidecar that could render right now, best first."""
    root = Path(root).resolve()
    found = []
    try:
        import manim                                     # noqa: F401
        found.append(Runtime("inprocess", f"manim {manim.__version__} in this interpreter", root))
    except Exception:                                    # noqa: BLE001 - any import failure means "no"
        pass
    vp = _venv_python(root)
    if vp.exists() and _has_manim(vp):
        found.append(Runtime("venv", f"manim in {VENV_DIR}", root))
    if _docker_ready():
        found.append(Runtime("docker", f"the {IMAGE} image", root))
    return found


def best(root: Path) -> Runtime | None:
    rs = discover(root)
    return rs[0] if rs else None


# --------------------------------------------------------------- provisioning

def can_provision(root: Path) -> list[str]:
    """Which sidecars this machine could still install, best first.

    A venv is tried first because it needs no daemon and no image: since manim
    0.19 the video is written through PyAV rather than an ffmpeg binary, so
    `pip install manim` is the whole system requirement. Docker is the fallback
    for machines where building a wheel would be the harder problem.
    """
    out = []
    # Debian splits ensurepip out into python3-venv, so a plain python3 cannot
    # make a virtualenv until that is installed — which apt can do.
    if find_spec("venv") and (find_spec("ensurepip") or _apt_available()):
        out.append("venv")
    if shutil.which("docker"):
        out.append("docker")
    return out


def provision(root: Path, kind: str | None = None, log: Log = _noop) -> Runtime:
    """Install a sidecar. Slow and network-bound, so it is always explicit."""
    root = Path(root).resolve()
    options = can_provision(root)
    if kind is not None and kind not in ("venv", "docker"):
        raise RuntimeError(f"unknown sidecar '{kind}' — expected venv or docker")
    if not options:
        raise RuntimeError(
            "nothing to install manim with: this machine has neither a working venv "
            "module nor docker. Install python3-venv or docker and try again.")
    order = [kind] if kind else options
    last: Exception | None = None
    for k in order:
        try:
            return _provision_venv(root, log) if k == "venv" else _provision_docker(root, log)
        except Exception as e:                           # noqa: BLE001
            last = e
            log(f"the {k} sidecar did not work out: {e}")
            # An explicit choice is honoured, not second-guessed.
            if kind or k == order[-1]:
                break
            log(f"falling back to the {order[order.index(k) + 1]} sidecar")
    raise RuntimeError(f"could not install a manim sidecar: {last}")


def _stream(argv: list[str], log: Log, cwd: Path | None = None) -> None:
    log(f"$ {' '.join(argv)}")
    proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, bufsize=1, cwd=cwd)
    assert proc.stdout is not None
    tail: list[str] = []
    for line in proc.stdout:
        line = line.rstrip("\n")
        tail = (tail + [line])[-40:]
        log(line)
    if proc.wait() != 0:
        raise RuntimeError(f"{argv[0]} failed:\n" + "\n".join(tail[-12:]))


# pycairo ships no Linux wheel: pip compiles it, which needs a C compiler and
# the cairo and pango headers. On a fresh Codespace none of that is present, so
# `pip install manim` fails on metadata generation with an error about missing
# compilers — which reads like a broken package rather than a missing apt line.
APT_DEPS = ["python3-venv", "python3-dev", "build-essential", "pkg-config",
            "libcairo2-dev", "libpango1.0-dev"]


def _apt_available() -> bool:
    return sys.platform == "linux" and bool(shutil.which("apt-get"))


def _missing_build_deps() -> list[str]:
    if not _apt_available():
        return []                                        # only Debian is handled here
    missing = []
    if not find_spec("ensurepip"):
        missing.append("python3-venv")
    # pycairo compiles against Python itself, so the headers have to be there.
    if not Path(sysconfig.get_paths()["include"], "Python.h").exists():
        missing.append("python3-dev")
    if not (shutil.which("cc") or shutil.which("gcc")):
        missing.append("build-essential")
    pkg_config = shutil.which("pkg-config")
    if not pkg_config:
        return missing + ["pkg-config", "libcairo2-dev", "libpango1.0-dev"]
    for dev, mod in (("libcairo2-dev", "cairo"), ("libpango1.0-dev", "pango")):
        if subprocess.run([pkg_config, "--exists", mod], capture_output=True).returncode != 0:
            missing.append(dev)
    return missing


def _apt_install(packages: list[str], log: Log) -> None:
    sudo = [] if os.geteuid() == 0 else ["sudo", "-n"]
    if sudo and not shutil.which("sudo"):
        raise RuntimeError(_apt_hint(packages))
    log(f"manim needs {', '.join(packages)} to build — installing them")
    env_prefix = ["env", "DEBIAN_FRONTEND=noninteractive"]
    try:
        _stream(sudo + env_prefix + ["apt-get", "update", "-qq"], log)
        _stream(sudo + env_prefix + ["apt-get", "install", "-y", "--no-install-recommends",
                                     *packages], log)
    except RuntimeError as e:
        raise RuntimeError(f"{e}\n\n{_apt_hint(packages)}") from e


def _apt_hint(packages: list[str]) -> str:
    return ("install these first, then try again:\n"
            f"  sudo apt-get install -y {' '.join(packages)}")


def _provision_venv(root: Path, log: Log) -> Runtime:
    missing = _missing_build_deps()
    if missing:
        _apt_install(missing, log)
    target = root / VENV_DIR
    if not _venv_python(root).exists():
        _stream([sys.executable, "-m", "venv", str(target)], log)
    py = str(_venv_python(root))
    _stream([py, "-m", "pip", "install", "--upgrade", "pip"], log)
    # No LaTeX: every scene is built from Text, Rectangle, Ellipse and Line, so
    # the two gigabytes of TeX Live that manim's docs assume are not needed.
    _stream([py, "-m", "pip", "install", "manim"], log)
    if not _has_manim(py):
        raise RuntimeError("pip finished but manim still does not import in the venv")
    log("manim is installed in " + str(VENV_DIR))
    return Runtime("venv", f"manim in {VENV_DIR}", root)


def _provision_docker(root: Path, log: Log) -> Runtime:
    _stream(["docker", "pull", IMAGE], log)
    if not _docker_ready():
        raise RuntimeError(f"docker pull finished but {IMAGE} is not present")
    log(f"pulled {IMAGE}")
    return Runtime("docker", f"the {IMAGE} image", root)


def status(root: Path) -> dict:
    """What the webpage shows on its video panel."""
    rs = discover(root)
    return {
        "ready": bool(rs),
        "kind": rs[0].kind if rs else None,
        "detail": rs[0].detail if rs else None,
        "available": [{"kind": r.kind, "detail": r.detail} for r in rs],
        "installable": can_provision(root),
    }


if __name__ == "__main__":                               # a quick human check
    print(json.dumps(status(Path(__file__).resolve().parents[2]), indent=2))
