# Known limitations

## Sharing saves

Link copies saves as plain files, with Android's all-files access (the same permission sync apps
such as Syncthing use), so it can only share what that permission lets it read and write. If it
can't read every file in an emulator's saves folder or write in its folders, the emulator's card says
*Emulator not supported* and its saves aren't synced either way, so nothing is overwritten. Backups
still run.

This depends on the device. On the Android 13 device tested, every emulator works. On Android 15,
files in `Android/data/<app>` keep the permissions their emulator gave them, and newer devices will
probably behave the same. Folders outside `Android/data` and SD cards are fine, so **if an emulator
lets you choose its data folder, pick one outside `Android/data`**. Android also doesn't promise that
all-files access reaches other apps' `Android/data`; the devices tested allow it.

| Emulator | Saves shared | Why |
|---|---|---|
| RetroArch | Yes | Saves in `RetroArch/saves` by default. |
| AetherSX2 / NetherSX2, ARMSX2 | Yes | Normal files. |
| Dolphin, PrimeHack, Eden | Yes | Normal files. |
| PPSSPP, Azahar, melonDS | Yes | They save to a folder you choose. |
| Flycast | Probably | Normal files, going by its code; not tried on a device. |
| DuckStation | Not on Android 15 | Its saves are readable only by DuckStation, and its folder can't be moved. |
| ARMSX3 | Not on Android 15 | Other apps can read its saves there, but not write into its folders. |
| ARMSX1 | No | Saves in the app's internal storage, out of reach of other apps. |
| Redream, DraStic, Citron, Vita3K | Not checked | If they let you choose a folder, pick one outside `Android/data`. |

Other sync apps, Shizuku and adb hit the same limit; only root would get around it, and Link doesn't
use root. For PS1 saves that sync both ways, use RetroArch with SwanStation or Beetle PSX HW.

## Listening in the background

Link listens as a foreground service and uses next to no battery while it waits. A manufacturer's
process cleaner, battery optimisation or a phone's autostart setting can still stop it, and on
Android 15 a stopped Link can't restart itself from the background; Ludolog wakes it when you return
to it. See [keep listening](manual.md#keep-listening-with-the-screen-off) in the user guide.

## Use it on your own network

Devices pair with a code, and every request after that needs the key they exchanged, but the traffic
itself travels unencrypted over your local network. Anyone else on the same Wi-Fi could read it, and
with it the key. At home that is fine; on a shared network (a hotel, an event), turn off **Sharing**
and **PC Link** until you are back.
