"""Makes DAO's game profile for building the game on the phone (build/profile/dao.zip), in the
layout of the recipe format (SD1/toolchain/RECIPE_FORMAT_DRAFT.md, format 2):

    recipe.json               the steps the launcher runs
    lib/libdao-game.a         the runtime and the front end, linked whole (static initializers)
    lib/libdao-deps.a         everything else the game library links: XenonUtils, Xenia's GPU
                              and UI, glslang, dxbc, snappy, imgui, fmt, xxhash, FFmpeg's
                              avcodec/avutil, linkernsbypass
    include/                  what the phone compiles the generated code with: mmio_hook.h,
                              SIMDe, the build descriptor (build_info*)
    xr/                       XenonRecomp's config template, switch tables, ppc_context.h
    licenses/                 the full licence text of each component above

The archives come from the Android build of the game library (build/tool/android, NDK 27.3,
the launcher's SDL 3.4.18), with debug info stripped. The phone translates the game's code
and compiles it itself; nothing of the game is in the profile.

    python -I scripts/make_dao_profile.py [--build build/tool/android] [--kit <kit folder>] [--check]

--check links a game library on this PC the way the phone will (the recipe's own link line,
the kit's sysroot, this PC's already compiled game code) and checks that nothing is undefined
and that the library carries the ABI note.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECOMP = ROOT / "recomp"
# DAORecomp's own tools on this PC; elsewhere (GitHub's CI) the ones on PATH (the NDK's LLVM).
LLVM = RECOMP / "tools" / "llvm" / "bin"
NINJA = RECOMP / "tools" / "ninja" / ("ninja.exe" if os.name == "nt" else "ninja")
DEFAULT_KIT = ROOT.parent / "SD1" / "toolchain" / "out" / "kit"
VERSION = "dao-11"
TITLE_ID = "454108C0"
# Linked whole into dao-game, or compiled on the phone: not in dao-deps.
# libdxbc.a: Xenia's DXBC checksum (AMD, no licence grant) is D3D12-only: the Vulkan build never
# keeps it, so it stays out of the profile (the link checks that under --no-undefined).
NOT_DEPS = {"libdaodroid-runtime.a", "libdaobuildinfo.a", "libdaoppc.a", "libdxbc.a"}
# P0 on the S25: this unit alone takes about 120 s, so it starts first.
COST_HINTS = {"ppc_recomp.17.cpp": 120}

INPUTS = ROOT / "profiles" / "dao"
XE_TP = RECOMP / "tools" / "xenia-canary" / "third_party"
XENON = RECOMP / "tools" / "XenonRecomp"
# What the profile's archives and headers hold, and each one's licence text: licenses/<name>.
# A file whose licence is only its source's opening comment gives that comment (header=True).
LICENSES = [
    ("XDroid360.txt", ROOT / "LICENSE", "the front end (android/app/src/main/cpp)", False),
    ("DAORecomp.txt", RECOMP / "LICENSE", "the runtime (recomp/DAORecomp)", False),
    ("Xenia.txt", RECOMP / "xenia" / "LICENSE", "the GPU, UI and base code", False),
    ("XenonRecomp.txt", XENON / "LICENSE.md", "XenonUtils (XEX loading), ppc_context.h", False),
    ("SIMDe.txt", XENON / "thirdparty" / "simde" / "COPYING", "include/simde", False),
    ("libmspack.txt", XENON / "thirdparty" / "libmspack" / "libmspack" / "COPYING.LIB", "LZX decompression (LGPL 2.1)", False),
    ("tiny-AES-c.txt", XENON / "thirdparty" / "tiny-AES-c" / "unlicense.txt", "AES", False),
    ("TinySHA1.txt", XENON / "thirdparty" / "TinySHA1" / "TinySHA1.hpp", "SHA-1", True),
    ("FFmpeg.txt", XE_TP / "FFmpeg" / "COPYING.LGPLv2.1", "avcodec and avutil (LGPL 2.1+)", False),
    ("glslang.txt", XE_TP / "glslang" / "LICENSE.txt", "SPIR-V builder", False),
    ("fmt.txt", XE_TP / "fmt" / "LICENSE", "formatting", False),
    ("xxHash.txt", XE_TP / "xxhash" / "LICENSE", "hashing", False),
    ("Snappy.txt", XE_TP / "snappy" / "COPYING", "compression", False),
    ("Dear-ImGui.txt", XE_TP / "imgui" / "LICENSE.txt", "UI", False),
    ("toml++.txt", XE_TP / "tomlplusplus" / "LICENSE", "settings files", False),
    ("Vulkan-Headers.txt", XE_TP / "Vulkan-Headers" / "LICENSE.md", "Vulkan headers", False),
    ("VulkanMemoryAllocator.txt", XE_TP / "VulkanMemoryAllocator" / "LICENSE.txt", "GPU memory", False),
    ("FidelityFX-CAS.txt", XE_TP / "FidelityFX-CAS" / "LICENSE.txt", "sharpening shader", False),
    ("FidelityFX-FSR.txt", XE_TP / "FidelityFX-FSR" / "license.txt", "upscaling shader", False),
    ("SDL.txt", RECOMP / "thirdparty" / "SDL" / "LICENSE.txt", "SDL 3 headers (the library is the launcher's)", False),
    ("linkernsbypass.txt", ROOT / "android" / "app" / "src" / "main" / "cpp" / "linkernsbypass" / "LICENSE", "linker namespaces", False),
    ("native-renderer-GPL-3.0.txt", ROOT / "native-renderer" / "LICENSE", "the native renderer (from nfsmw-android)", False),
    ("native-renderer-NOTICE.md", ROOT / "native-renderer" / "NOTICE.md", "the native renderer's origin and credits", False),
]


def opening_comment(path):
    """A source file's leading comment lines: the licence of a library that has no licence file."""
    lines = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if lines and not line.strip().startswith(("/", "*", "**")) and line.strip():
            break
        lines.append(line)
        if len(lines) > 120:
            break
    return "\n".join(lines) + "\n"


def git_head(path):
    r = subprocess.run(["git", "-C", str(path), "rev-parse", "HEAD"], capture_output=True, text=True)
    return r.stdout.strip() or "unknown"


def write_licenses(folder):
    """licenses/: each component's full licence text, and README.txt naming them (with
    FFmpeg's exact source, as the LGPL asks)."""
    folder.mkdir()
    rows = []
    for name, src, what, header in LICENSES:
        if not src.is_file():
            sys.exit(f"No licence text at {src}")
        text = opening_comment(src) if header else src.read_text(encoding="utf-8", errors="replace")
        (folder / name).write_text(text, encoding="utf-8", newline="\n")
        rows.append(f"  {name:28} {what}")
    ffmpeg = XE_TP / "FFmpeg"
    version = (ffmpeg / "RELEASE").read_text(encoding="utf-8").strip() if (ffmpeg / "RELEASE").is_file() else "?"
    readme = ("Licences of what this profile's archives and headers hold (DAO's game library is linked\n"
              "from them on the phone). No game code or data is in the profile.\n\n" + "\n".join(rows) + "\n\n"
              f"FFmpeg {version}, the has207/FFmpeg fork, commit {git_head(ffmpeg)}:\n"
              "  source: https://github.com/has207/FFmpeg (configured as LGPL: no GPL parts).\n"
              "  lib/libdao-deps.a holds its objects, so the game library can be linked again with\n"
              "  another build of avcodec/avutil.\n")
    (folder / "README.txt").write_text(readme, encoding="utf-8", newline="\n")


def exe(name):
    local = LLVM / (name + (".exe" if os.name == "nt" else ""))
    return str(local) if local.is_file() else name


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# The static libraries the game library links, as a build of the app's CMake names them. A
# profile build (-DXDROID360_PROFILE_BUILD=ON) has no game library to read them from; a full build's
# link line is checked against this list, so it can't drift.
PROFILE_LIBS = [
    "runtime/libdaodroid-runtime.a", "liblinkernsbypass.a", "runtime/libXenonUtils.a",
    "xenia-vulkan/libxenia-gpu-vulkan.a", "xenia-vulkan/libxenia-gpu.a", "xenia-vulkan/libglslang-spirv.a",
    "xenia-vulkan/libxxhash.a", "xenia-vulkan/libsnappy.a",
    "xenia-vulkan/libxenia-ui-vulkan.a", "xenia-vulkan/libxenia-ui.a", "xenia-vulkan/libimgui.a",
    "xenia-vulkan/libxenia-base.a", "xenia-vulkan/libfmt.a", "runtime/liblibavcodec.a", "runtime/liblibavutil.a",
    # The native renderer (GPL-3.0, from nfsmw-android): off unless config.toml asks for it.
    "native-renderer/libnative-renderer.a",
]


def link_libraries(build):
    """The static libraries of the profile; checked against libgame.so's link in a full build."""
    text = (build / "build.ninja").read_text(encoding="utf-8", errors="replace")
    m = re.search(r"^build libgame\.so:.*?^  LINK_LIBRARIES = (.*?)$", text, re.M | re.S)
    if m:
        linked = {w for w in m.group(1).split() if w.endswith(".a") and Path(w).name not in NOT_DEPS}
        expected = {l for l in PROFILE_LIBS if Path(l).name not in NOT_DEPS}
        if linked != expected:
            sys.exit(f"The game library links other libraries than PROFILE_LIBS: "
                     f"missing {sorted(linked - expected)}, extra {sorted(expected - linked)}")
    return PROFILE_LIBS


def read_inputs():
    """profiles/dao: the supported default.xex files' hashes and the generated headers' (none of it
    game code). The first hash is the disc the translation is made from; the others are discs whose
    default.xex was checked to translate to the very same code."""
    xex = (INPUTS / "default.xex.sha256").read_text(encoding="utf-8").split()
    if not xex or not all(re.fullmatch(r"[0-9a-f]{64}", h) for h in xex):
        sys.exit(f"{INPUTS / 'default.xex.sha256'} holds no SHA-256, or something else")
    heads = ("ppc_config.h", "ppc_context.h")
    # ppc_tu/: the same two headers from Title Update 6's or 7's translation (config/DAO_tu6.toml).
    return (xex, {h: sha256(INPUTS / "ppc" / h) for h in heads},
            {h: sha256(INPUTS / "ppc_tu" / h) for h in heads})


def refresh_inputs():
    """profiles/dao again, from the supported disc's default.xex and a new translation (recomp/ppc)."""
    xex = ROOT / "install" / "game" / "default.xex"
    if not xex.is_file():
        sys.exit(f"No {xex}")
    (INPUTS / "ppc").mkdir(parents=True, exist_ok=True)
    # The other discs' hashes stay: they were checked to translate to the same code.
    listed = INPUTS / "default.xex.sha256"
    others = listed.read_text(encoding="utf-8").split()[1:] if listed.is_file() else []
    listed.write_text("".join(h + "\n" for h in [sha256(xex)] + others), encoding="utf-8", newline="\n")
    for h in ("ppc_config.h", "ppc_context.h"):
        shutil.copy2(RECOMP / "ppc" / h, INPUTS / "ppc" / h)
    print(f"{INPUTS}: written from {xex} and recomp/ppc")


def merge(archives, out):
    """One archive with every member of archives (llvm-ar's MRI script)."""
    out.unlink(missing_ok=True)
    script = "CREATE " + str(out) + "\n" + "".join(f"ADDLIB {a}\n" for a in archives) + "SAVE\nEND\n"
    subprocess.run([exe("llvm-ar"), "-M"], input=script, text=True, check=True)
    subprocess.run([exe("llvm-strip"), "-g", str(out)], check=True)


def toml_template(tu=False):
    """config/DAO.toml (or DAO_tu6.toml, with the title update's patch) with its paths pointing at
    the phone's folders (absolute, as is)."""
    name = "DAO_tu6" if tu else "DAO"
    text = (RECOMP / "config" / f"{name}.toml").read_text(encoding="utf-8")
    keys = [("file_path", "${GAME}/game/default.xex"),
            ("out_directory_path", "${WORK}/ppc_tu" if tu else "${WORK}/ppc"),
            ("switch_table_file_path", f"${{PROFILE}}/xr/{name}_switch_tables.toml")]
    if tu:
        keys.append(("patch_file_path", "${GAME}/title_update/default.xexp"))
    for key, value in keys:
        text, n = re.subn(rf"^{key}\s*=.*$", f'{key} = "{value}"', text, count=1, flags=re.M)
        if n != 1:
            sys.exit(f"config/{name}.toml has no {key}")
    return text


def recipe(kit, xex_sha256s, headers, headers_tu):
    cxx = ["--target=aarch64-none-linux-android29", "--sysroot=${KIT}/sysroot",
           "-resource-dir", "${KIT}/clang/22",
           "-DANDROID", "-fdata-sections", "-ffunction-sections", "-funwind-tables",
           "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2", "-O3", "-DNDEBUG",
           "-std=gnu++20", "-fPIC"]

    # In vars, not in the steps: RecipeRunner.firstProfileStep looks for ${PROFILE}/include in the
    # steps' own text, and what comes before that step is reused by "Update game".
    def ppcflags(ppc):
        return ["-fno-strict-aliasing", "-ffp-model=strict", "-ffp-contract=off",
                "-Wno-unused-label", "-Wno-unused-variable", "-Wno-unused-but-set-variable",
                f"-I{ppc}", "-I${PROFILE}/include/simde",
                "-include", "${PROFILE}/include/mmio_hook.h"]

    def pch(folder):
        return ["-Xclang", "-include-pch", "-Xclang", f"{folder}/pch.hxx.pch",
                "-Xclang", "-include", "-Xclang", f"{folder}/pch.hxx"]

    flags = {"PPCFLAGS": ppcflags("${WORK}/ppc"), "PCH": pch("${WORK}/pch"),
             "PPCFLAGS_TU": ppcflags("${WORK}/ppc_tu"), "PCH_TU": pch("${WORK}/pch_tu")}

    builtins = "${KIT}/clang/lib/libclang_rt.builtins-aarch64-android.a"
    lib = "${KIT}/sysroot/usr/lib/aarch64-linux-android"
    update = "${GAME}/title_update/default.xexp"

    def code_steps(tu):
        """Translating, compiling and linking the game's code: the disc's, or with Title Update 6 or
        7 installed (${GAME}/title_update, where the STFS reader puts it) only the title update's,
        in folders of its own, linked with the runtime built for it (libdao-game-tu.a,
        DAO_TU_ONLY). Exactly one of the two sets runs, by whether the title update is there.
        Returned as (compiling, linking): both sets' compiling steps go before either's linking
        steps, the first to read the profile's include/ and lib/ (see flags)."""
        s = "-tu" if tu else ""
        v = "_TU" if tu else ""
        ppc = "${WORK}/ppc" + ("_tu" if tu else "")
        pch_dir = "${WORK}/pch" + ("_tu" if tu else "")
        heads = headers_tu if tu else headers
        when = {"exists": update} if tu else {"missing": update}
        toml = "DAO_tu6.toml" if tu else "DAO.toml"
        steps = [
            {"id": "xr-config" + s, "kind": "write", "weight": 0, "creates": ["${WORK}/xr"],
             "path": "${WORK}/xr/" + toml, "from": "${PROFILE}/xr/" + toml + ".in"},
            {"id": "recomp" + s, "kind": "run", "label": "Translating game code", "weight": 10,
             "tool": "xenonrecomp", "args": ["${WORK}/xr/" + toml, "${PROFILE}/xr/ppc_context.h"],
             "creates": [ppc], "progress": "Recompiling functions\\.\\.\\. ([0-9.]+)%"},
            # The prebuilt runtime is compiled against these generated headers (stdafx.h).
            {"id": "check-generated" + s, "kind": "verify", "weight": 0,
             "files": {ppc + "/ppc_config.h": [heads["ppc_config.h"]],
                       ppc + "/ppc_context.h": [heads["ppc_context.h"]]},
             "message": "profile_mismatch"},
            {"id": "pch-stub" + s, "kind": "write", "weight": 0, "creates": [pch_dir],
             "path": pch_dir + "/pch.hxx",
             "text": "#pragma clang system_header\n#include \"" + ppc + "/ppc_recomp_shared.h\"\n"},
            {"id": "pch" + s, "kind": "run", "label": "Preparing compiler", "weight": 2, "tool": "clang",
             "args": ["@CXXFLAGS", "@PPCFLAGS" + v, "-x", "c++-header", pch_dir + "/pch.hxx",
                      "-o", pch_dir + "/pch.hxx.pch"]},
            {"id": "game-code" + s, "kind": "each", "label": "Compiling game code", "weight": 180,
             "tool": "clang",
             "inputs": {"glob": ppc + "/ppc_recomp.*.cpp", "plus": [ppc + "/ppc_func_mapping.cpp"]},
             "args": ["@CXXFLAGS", "@PPCFLAGS" + v, "@PCH" + v, "-c", "${IN}", "-o", "${OBJ}"],
             "objects": "${WORK}/obj/game-code" + s,
             "schedule": "longest_first", "cost_hints": {} if tu else COST_HINTS, "fail": "stop"},
        ]
        linking = [
            {"id": "build-info" + s, "kind": "each", "label": "Compiling game code", "weight": 2,
             "tool": "clang",
             "inputs": {"plus": ["${PROFILE}/include/build_info_android.cpp"]},
             "args": ["@CXXFLAGS", f"-I{ppc}", "-I${PROFILE}/include", "-I${PROFILE}/include/simde"]
                     + (["-DDAO_HAS_TU6=1"] if tu else [])
                     + ["-DDAO_BUILD_INFO=" + ("DaoBuildTu6" if tu else "DaoBuildDisc"),
                        "-DDAO_BUILD_MAPPINGS=PPCFuncMappings", "-c", "${IN}", "-o", "${OBJ}"],
             "objects": "${WORK}/obj/build-info" + s},
            {"id": "link" + s, "kind": "run", "label": "Linking", "weight": 8, "tool": "lld",
             "message": "link_failed",
             "args": ["-flavor", "gnu",
                      "--sysroot=${KIT}/sysroot", "-EL", "--fix-cortex-a53-843419",
                      "-z", "now", "-z", "relro", "-z", "max-page-size=16384",
                      "--hash-style=gnu", "--eh-frame-hdr", "-m", "aarch64linux", "-shared",
                      "-soname", "libgame.so", "--build-id=sha1", "--no-rosegment",
                      "--no-undefined", "--gc-sections", "-Bsymbolic", "--strip-debug",
                      "-o", "${WORK}/libgame.so",
                      lib + "/29/crtbegin_so.o", "-L" + lib + "/29", "-L" + lib,
                      "--whole-archive", "${PROFILE}/lib/libdao-game" + s + ".a", "--no-whole-archive",
                      "@objects:build-info" + s, "@objects:game-code" + s,
                      "${PROFILE}/lib/libdao-deps.a",
                      "${TOOLS}/libSDL3.so",
                      "-landroid", "-llog", "-ldl", "-latomic", "-lm",
                      "-Bstatic", "-lc++", "-Bdynamic", "-lm",
                      builtins, "-l:libunwind.a", "-ldl", "-lc",
                      builtins, "-l:libunwind.a", "-ldl",
                      lib + "/29/crtend_so.o"]},
        ]
        for step in steps + linking:
            step["when"] = when
        return steps, linking

    disc, tu = code_steps(False), code_steps(True)
    return {
        "format": 2,
        "version": VERSION,
        "game": {"id": "dao", "title": "Dragon Age: Origins", "title_ids": [TITLE_ID]},
        "source": {"formats": ["xiso"], "pick": "file"},
        "requires": {"kit": kit["kit"], "abi": abi_version(),
                     "tools": {"clang": kit["tools"]["clang"], "xenonrecomp": kit["tools"]["xenonrecomp"]}},
        # Game files: 6.4 GB on the PC, plus the native renderer's shader library (14.6 MB);
        # work: 253 MB of generated C++, 378 MB of objects (kept for "Update game"), the PCH.
        "space": {"game_mb": 6815, "work_mb": 1000, "install_mb": 300},
        "inputs": {"xex": {"path": "${GAME}/game/default.xex", "sha256": xex_sha256s}},
        "vars": {"CXXFLAGS": cxx, **flags},
        "jobs": {"max_rss_mb": 400, "reserve_mb": 768},
        "steps": [
            {"id": "extract", "kind": "extract", "label": "Extracting game files", "weight": 120,
             "format": "xiso", "to": "${GAME}", "keeps_game": True},
            {"id": "check-xex", "kind": "verify", "label": "Checking game version", "weight": 1,
             "files": {"${GAME}/game/default.xex": xex_sha256s},
             "message": "unsupported_game_version"},
            # The native renderer's shader library from the game's own shaders (libshaderlib.so,
            # XenosRecomp and DXC: about 11 s on an S25). Again on "Update game" if the translator
            # (libshaderlib.so) is not the one that made the library, or the library is gone
            # (rerun, outputs: RecipeRunner.rerunCurrent).
            {"id": "shaders", "kind": "run", "label": "Translating shaders", "weight": 2,
             "tool": "shaderlib", "rerun": True, "creates": ["${GAME}/userdata"],
             "outputs": ["${GAME}/userdata/nfsmw_shaders.nfsp"],
             "args": ["${GAME}/game/packages/core/data/shaders.erf", "${GAME}/userdata/nfsmw_shaders.nfsp",
                      "-j", "${JOBS}"],
             "progress": "progress ([0-9]+)/([0-9]+)"},
        ] + disc[0] + tu[0] + disc[1] + tu[1],
        "result": {"library": "${WORK}/libgame.so", "install": "${INSTANCE}/libgame.so", "keep": ["${WORK}/logs"]},
    }


def abi_version():
    text = (ROOT / "android" / "app" / "src" / "main" / "cpp" / "dao_abi.h").read_text(encoding="utf-8")
    return int(re.search(r"#define\s+DAO_ABI_VERSION\s+(\d+)", text).group(1))


def stage(build, kit, out):
    libs = link_libraries(build)
    targets = ["dao-front", "dao-front-tu", "runtime/libdaodroid-runtime-tu.a"] + libs
    subprocess.run([str(NINJA) if NINJA.is_file() else "ninja", "-C", str(build)] + targets, check=True)
    deps = [build / l for l in libs if Path(l).name not in NOT_DEPS]
    runtime = [build / l for l in libs if Path(l).name == "libdaodroid-runtime.a"]
    if not runtime:
        sys.exit("The game library doesn't link libdaodroid-runtime.a")
    xex_sha256s, headers, headers_tu = read_inputs()

    shutil.rmtree(out, ignore_errors=True)
    (out / "lib").mkdir(parents=True)
    merge([build / "libdao-front.a"] + runtime, out / "lib" / "libdao-game.a")
    # The runtime and the front end for a game built with Title Update 6 or 7 (DAO_TU_ONLY).
    merge([build / "libdao-front-tu.a", build / "runtime" / "libdaodroid-runtime-tu.a"],
          out / "lib" / "libdao-game-tu.a")
    merge(deps, out / "lib" / "libdao-deps.a")

    inc = out / "include"
    inc.mkdir()
    for f in ("mmio_hook.h", "build_info.h", "build_info.cpp"):
        shutil.copy2(RECOMP / "DAORecompLib" / f, inc / f)
    shutil.copy2(ROOT / "android" / "runtime" / "build_info_android.cpp", inc / "build_info_android.cpp")
    shutil.copytree(RECOMP / "tools" / "XenonRecomp" / "thirdparty" / "simde", inc / "simde",
                    ignore=shutil.ignore_patterns(".git", ".github", "test", "*.md"))

    xr = out / "xr"
    xr.mkdir()
    (xr / "DAO.toml.in").write_text(toml_template(), encoding="utf-8", newline="\n")
    shutil.copy2(RECOMP / "config" / "DAO_switch_tables.toml", xr / "DAO_switch_tables.toml")
    (xr / "DAO_tu6.toml.in").write_text(toml_template(tu=True), encoding="utf-8", newline="\n")
    shutil.copy2(RECOMP / "config" / "DAO_tu6_switch_tables.toml", xr / "DAO_tu6_switch_tables.toml")
    shutil.copy2(RECOMP / "tools" / "XenonRecomp" / "XenonUtils" / "ppc_context.h", xr / "ppc_context.h")

    write_licenses(out / "licenses")

    r = recipe(kit, xex_sha256s, headers, headers_tu)
    (out / "recipe.json").write_text(json.dumps(r, indent=2) + "\n", encoding="utf-8", newline="\n")
    return r


def pack(folder, zip_path):
    zip_path.unlink(missing_ok=True)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(folder / "recipe.json", "recipe.json")  # first: the launcher reads it alone
        for p in sorted(folder.rglob("*")):
            if p.is_file() and p.name != "recipe.json":
                z.write(p, p.relative_to(folder).as_posix())


# ---------------------------------------------------------------- --check

def expand(args, vars_, objects, extra=None):
    out = []
    for a in args:
        if a.startswith("@objects:"):
            out += objects[a[len("@objects:"):]]
        elif a.startswith("@"):
            out += [expand_text(x, vars_, extra) for x in vars_[a[1:]]]
        else:
            out.append(expand_text(a, vars_, extra))
    return out


def expand_text(s, vars_, extra=None):
    def sub(m):
        name = m.group(1)
        v = (extra or {}).get(name, vars_.get(name))
        if not isinstance(v, str):
            sys.exit(f"recipe: ${{{name}}} is {'unset' if v is None else 'a list'}")
        return expand_text(v, vars_, extra) if name not in (extra or {}) else v
    return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", sub, s)


def check(build, kit_dir, profile, r):
    """Links libgame.so on this PC with the recipe's own build-info and link steps."""
    with tempfile.TemporaryDirectory(prefix="daoprofile") as tmp:
        tmp = Path(tmp)
        work = tmp / "work"
        (work / "obj" / "game-code").mkdir(parents=True)
        (work / "obj" / "build-info").mkdir(parents=True)
        vars_ = {"KIT": kit_dir.as_posix(), "PROFILE": profile.as_posix(), "WORK": work.as_posix(),
                 "TOOLS": (build / "sdl").as_posix(), "GAME": "", "INSTANCE": tmp.as_posix(), "JOBS": "8"}
        vars_.update(r["vars"])
        # The game code: this PC's objects of the same units, the same flags.
        game = work / "obj" / "game-code"
        subprocess.run([exe("llvm-ar"), "x", "--output", str(game), str(build / "ppc" / "libdaoppc.a")], check=True)
        # ${WORK}/ppc is the phone's translated code; the build descriptor needs only its headers.
        (work / "ppc").mkdir()
        for h in (RECOMP / "ppc").glob("*.h"):
            shutil.copy2(h, work / "ppc" / h.name)
        if not (build / "ppc" / "libdaoppc.a").is_file():
            sys.exit("check: needs a full build's compiled game code (ppc/libdaoppc.a)")
        steps = {s["id"]: s for s in r["steps"]}
        obj = work / "obj" / "build-info" / "build_info_android.o"
        args = expand(steps["build-info"]["args"], vars_, {},
                      {"IN": (profile / "include" / "build_info_android.cpp").as_posix(), "OBJ": obj.as_posix()})
        subprocess.run([exe("clang")] + args, check=True)
        objects = {"build-info": [obj.as_posix()],
                   "game-code": sorted(p.as_posix() for p in game.iterdir() if p.suffix == ".o")}
        link = expand(steps["link"]["args"], vars_, objects)
        rsp = tmp / "link.rsp"
        rsp.write_text("\n".join(f'"{a}"' for a in link[2:]), encoding="utf-8")
        result = subprocess.run([exe("ld.lld"), "@" + str(rsp)], capture_output=True, text=True)
        if result.returncode != 0:
            print(result.stdout + result.stderr)
            sys.exit("check: the link failed")
        lib = work / "libgame.so"
        notes = subprocess.run([exe("llvm-readelf"), "-n", str(lib)], capture_output=True, text=True).stdout
        if "XDroid360" not in notes:
            sys.exit("check: no XDroid360 note in the library")
        print(f"check: linked {lib.stat().st_size >> 20} MB, {len(objects['game-code'])} game units, ABI note present")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--build", default=str(ROOT / "build" / "tool" / "android"))
    ap.add_argument("--kit", default=str(DEFAULT_KIT))
    ap.add_argument("--out", default=str(ROOT / "build" / "profile"))
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--refresh-inputs", action="store_true", help="rewrite profiles/dao from install/game and recomp/ppc")
    args = ap.parse_args()
    if args.refresh_inputs:
        refresh_inputs()
    build, kit_dir, out = Path(args.build), Path(args.kit), Path(args.out)
    kit = json.loads((kit_dir / "kit.json").read_text(encoding="utf-8"))
    folder = out / "dao"
    r = stage(build, kit, folder)
    pack(folder, out / "dao.zip")
    print(f"{out / 'dao.zip'}: {(out / 'dao.zip').stat().st_size >> 20} MB")
    if args.check:
        check(build, kit_dir, folder, r)


if __name__ == "__main__":
    main()
