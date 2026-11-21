# StorageWizard

A lightweight, cross-platform desktop utility built with **Java 17+ (Swing)** to inspect drives, clean developer junk folders, compress files/directories with Zstd, and optimize heavy media files with FFmpeg — 100% offline.

## Features

- **System Requirements Check:** Automatically detects Java 17+, `ffmpeg`, and `zstd` on startup.
- **Scan:** Drive and partition overview powered by OSHI.
- **My Stuff:** Filter files and full directories by name/glob, size, extension, age, or type (`All`, `Files`, `Directories`), compress with `zstd` / `tar --zstd`, and safely delete recursively.
- **Projects:** Scans repositories using stack presets (`Node/JS`, `Java/JVM`, `Python`, `Rust/C++`) and purges build/cache folders (`node_modules`, `target`, `.venv`, `.terraform`, etc.) without touching source code.
- **Media Optimizer:** Finds video files (`mp4`, `mkv`, `avi`, `mov`, `webm`, etc.) and re-encodes them asynchronously via FFmpeg (H.264 CRF 28).
- **Options & Themes:** Custom scan directories persisted locally (`~/.config/storagewizard/settings.properties`) and instant switching between **Catppuccin** and **Gruvbox** themes.

## Requirements

- **Java 17+** (required to run the application)
- **FFmpeg** (optional, required for *Media Optimizer*)
- **Zstd** (optional, required for *My Stuff* compression)

### Quick Install of Dependencies

```bash
# Arch / CachyOS
sudo pacman -S jre17-openjdk ffmpeg zstd

# Ubuntu / Debian
sudo apt install openjdk-17-jre ffmpeg zstd

# Fedora
sudo dnf install java-17-openjdk ffmpeg zstd

# Windows
winget install EclipseAdoptium.Temurin.17.JRE Gyan.FFmpeg Facebook.Zstandard
```

## Run

Download `storagewizard.jar` from the **Releases** page and run:

```bash
java -jar storagewizard.jar
```

## Build from Source

Requires Maven 3.8+ and JDK 17+:

```bash
mvn clean package
java -jar target/storagewizard.jar
```
