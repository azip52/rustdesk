#!/usr/bin/env python3

import argparse
import os
import platform
import shutil
import sys
from pathlib import Path


WINDOWS = platform.system() == "Windows"
HBB_NAME = "rustdesk.exe"
EXE_PATH = Path("target/release") / HBB_NAME
FLUTTER_BUILD_DIR = Path("flutter/build/windows/x64/runner/Release")


def system2(cmd: str):
    exit_code = os.system(cmd)
    if exit_code != 0:
        sys.stderr.write(f"Error occurred when executing: `{cmd}`. Exiting.\n")
        sys.exit(1)


def get_version():
    with open("Cargo.toml", encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("version"):
                return line.replace("version", "").replace("=", "").replace('"', "").strip()
    return ""


def make_parser():
    parser = argparse.ArgumentParser(description="Build RustDesk for Windows x64.")
    parser.add_argument(
        "-f",
        "--feature",
        dest="feature",
        metavar="N",
        type=str,
        nargs="+",
        default="",
        help="Additional Cargo features.",
    )
    parser.add_argument("--flutter", action="store_true", help="Build Flutter Windows package")
    parser.add_argument("--hwcodec", action="store_true", help="Enable hwcodec feature")
    parser.add_argument("--vram", action="store_true", help="Enable vram feature")
    parser.add_argument("--portable", action="store_true", help="Build portable executable")
    parser.add_argument("--skip-cargo", action="store_true", help="Skip Cargo build")
    parser.add_argument(
        "--skip-portable-pack",
        action="store_true",
        help="Skip self-extracted executable packing",
    )
    return parser


def get_features(args):
    features = [] if args.flutter else ["inline"]
    if args.feature:
        features.extend(args.feature)
    if args.hwcodec:
        features.append("hwcodec")
    if args.vram:
        features.append("vram")
    if args.flutter:
        features.append("flutter")
    print("features:", features)
    return ",".join(features)


def cargo_build(features: str, lib_only=False):
    lib_arg = " --lib" if lib_only else ""
    feature_arg = f" --features {features}" if features else ""
    system2(f"cargo build --locked{feature_arg}{lib_arg} --release")


def build_virtual_display_dll():
    os.chdir("libs/virtual_display/dylib")
    system2("cargo build --locked --release")
    os.chdir("../../..")


def copy_virtual_display_dll():
    source = Path("target/release/deps/dylib_virtual_display.dll")
    if not source.exists():
        sys.stderr.write(f"Missing virtual display DLL: {source}\n")
        sys.exit(1)
    shutil.copy2(source, FLUTTER_BUILD_DIR)


def build_portable(version: str, source_dir: Path):
    os.chdir("libs/portable")
    system2("pip3 install -r requirements.txt")
    system2(f"python3 ./generate.py -f ../../{source_dir} -o . -e ../../{source_dir}/rustdesk.exe")
    os.chdir("../..")

    output = Path(f"rustdesk-{version}-x86_64.exe")
    if output.exists():
        output.unlink()
    Path("target/release/rustdesk-portable-packer.exe").rename(output)
    print(f"output location: {output.resolve()}")


def build_flutter_windows(version: str, features: str, skip_cargo: bool, skip_portable_pack: bool):
    if not skip_cargo:
        cargo_build(features, lib_only=True)
        if not Path("target/release/librustdesk.dll").exists():
            sys.stderr.write("cargo build failed: target/release/librustdesk.dll was not produced.\n")
            sys.exit(1)

    os.chdir("flutter")
    system2("flutter build windows --release")
    os.chdir("..")
    copy_virtual_display_dll()

    if not skip_portable_pack:
        build_portable(version, FLUTTER_BUILD_DIR)


def main():
    if not WINDOWS:
        sys.stderr.write("This fork is configured for Windows builds only.\n")
        sys.exit(1)

    args = make_parser().parse_args()
    version = get_version()
    features = get_features(args)

    if EXE_PATH.exists():
        EXE_PATH.unlink()

    build_virtual_display_dll()

    if args.flutter:
        build_flutter_windows(version, features, args.skip_cargo, args.skip_portable_pack)
        return

    cargo_build(features)

    if args.portable:
        resources_dir = Path("resources")
        if resources_dir.exists():
            shutil.rmtree(resources_dir)
        resources_dir.mkdir()
        shutil.copy2("target/release/rustdesk.exe", resources_dir)
        build_portable(version, resources_dir)


if __name__ == "__main__":
    main()
