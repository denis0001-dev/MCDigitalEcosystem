# MCDigital Ecosystem - QEMU Computer Block Mod

A Minecraft Forge 1.20.1 mod that adds a "Computer" block capable of running QEMU virtual machines. The block displays the VM screen on its texture and captures input when clicked.

## Features

- **Computer Block**: Place a computer block that can run QEMU virtual machines
- **VM Display**: The VM screen is displayed on the front face of the block (stretched to fill)
- **Input Capture**: When right-clicking the block, all keyboard and mouse input is captured and forwarded to the VM
- **State Persistence**: VM state is automatically saved when the world is saved and restored on load
- **Automatic Shutdown**: When the block is broken, the VM is forcefully shut down
- **Bundled QEMU**: QEMU binaries are bundled with the mod for Windows, Linux, and macOS
- **SPICE Protocol**: Uses libspice-client-glib via JNI for full SPICE protocol support

## Requirements

- Minecraft 1.20.1
- Minecraft Forge 47.2.0 or later
- Kotlin for Forge 3.12.0 or later
- **libspice-client-glib-2.0** (must be installed on the system)
- QEMU (bundled with mod or system installation)

## Building

### Prerequisites

1. Install libspice-client-glib development packages:
   - **Linux**: `sudo apt-get install libspice-client-glib-2.0-dev` (Debian/Ubuntu) or `sudo yum install spice-glib-devel` (RHEL/CentOS)
   - **macOS**: `brew install spice-gtk`
   - **Windows**: Install from [SPICE Windows builds](https://www.spice-space.org/download.html)

2. Ensure Java JDK 17+ is installed

3. Build the native library:
   ```bash
   make
   ```

4. Build the mod:
   ```bash
   ./gradlew build
   ```

The mod JAR will be in `build/libs/`. The native library will be in `build/libs/libspiceclient.so` (Linux), `libspiceclient.dylib` (macOS), or `spiceclient.dll` (Windows).

## Installation

1. Install Minecraft Forge 1.20.1
2. Install libspice-client-glib-2.0 on your system
3. Place the mod JAR in your `mods` folder
4. Place the native library (`libspiceclient.so`/`libspiceclient.dylib`/`spiceclient.dll`) in a location where Java can find it:
   - Same directory as the mod JAR
   - Or set `-Djava.library.path=/path/to/library` when launching Minecraft
5. Launch Minecraft

## Usage

1. Craft or obtain a Computer block
2. Place the block in your world
3. Right-click the block to interact with it
4. The VM will start automatically
5. Your input will be captured and sent to the VM
6. The VM screen will be displayed on the front face of the block

## Configuration

VM configuration can be customized per-block (future feature):
- RAM allocation (default: 512MB)
- Disk size (default: 10GB)
- OS image path

## Technical Details

- **Display**: Uses SPICE protocol via libspice-client-glib (JNI bindings)
- **Frame Rate**: Limited to 60 FPS for performance
- **Input**: Keyboard and mouse input forwarded via SPICE protocol
- **State Management**: Uses QEMU's savevm/loadvm functionality
- **Platform Support**: Windows, Linux, macOS (x86_64 and ARM64 for macOS)

## Native Library

The mod uses JNI to interface with libspice-client-glib. The native library must be:
1. Built using the provided Makefile
2. Available at runtime (either in the same directory as the mod or via `java.library.path`)

To rebuild the native library:
```bash
make clean
make
```

## Limitations

- Requires libspice-client-glib-2.0 to be installed on the system
- Native library must be built for each platform
- Only one player can control a computer block at a time
- VM state files are stored in the world save directory

## License

MIT License
