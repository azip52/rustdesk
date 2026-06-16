# RustDesk Windows Custom Client

This fork is trimmed for one build target only:

```text
rustdesk-x.x.x-x86_64.exe
```

The GitHub Actions workflow in `.github/workflows/flutter-build.yml` builds the Windows x64 Flutter client on `windows-2022` and uploads the self-extracted executable as an artifact.

## Build With GitHub Actions

1. Push to `master`, push a `v*` tag, or run the workflow manually from the GitHub Actions tab.
2. Open the `Build Windows x64 RustDesk` workflow run.
3. Download the `rustdesk-x.x.x-x86_64` artifact.

The version is read from `Cargo.toml`, so `version = "1.4.7"` produces:

```text
rustdesk-1.4.7-x86_64.exe
```

## Kept Build Pieces

- `flutter/windows`: Windows Flutter runner.
- `build.py`: RustDesk build orchestration used by the workflow.
- `vcpkg.json` and `res/vcpkg`: Windows dependency build inputs.
- `libs/portable`: self-extracted executable packer.

## Removed Build Pieces

- Android, iOS, Linux, and macOS Flutter platform directories.
- AppImage, Flatpak, F-Droid, Docker, MSI, Linux package, and macOS packaging resources.
- Non-Windows GitHub Actions workflows.
