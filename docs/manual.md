# Ludolog Link user guide

Ludolog Link shares saves, Companion sessions, game info and ROMs between your devices and your PC,
over your own Wi-Fi. It has an app for each device, next to Ludolog, and an app for Windows. Your
saves, sessions and games never go through the internet; only scraping covers and videos on the PC
(when you ask) and a daily update check (which can be turned off) do.

## First launch

Install Ludolog and open it once first. Link's setup then asks for:

- **Access**: *Files* (required: ROMs, saves and Ludolog's folder), *Notifications* (pairing codes
  and save conflicts), and the settings that [keep Link listening](#keep-listening-with-the-screen-off).
- **Name**: how your other devices and the PC show this one.
- **ROM folder**: where incoming ROMs go, one folder per console.
- **Pair**: another device, the PC, or both. This can wait.

## Pairing

- **Two devices**: same Wi-Fi, Link open on both. On one, tap *Pair a device…*, pick the other and
  enter the 6-digit code it shows. If a device is paired with two others that aren't paired with each
  other, *Pair them all* introduces them without codes.
- **The PC**: turn on PC Link on the device, click *Search* in the PC app, pick the device and enter
  the code it shows. Only once.

## On the device

- **Link** tab: status (including PC Link), paired devices (*Sync now*, the *Sharing* switch), the
  ROM folder, paired PCs and Settings (look, *PC Link auto-off*, *Run setup again*, About).
- **Saves**: each emulator's saves folder and backups.
- **ROMs**: *Get* games from another device, or *Request* them from the PC catalog (they arrive while
  the PC app is open). They come with their cover and video.
- **Log**: what happened here and on your paired devices, with errors in red.

With a device paired, Link listens on its own. When you finish a game, its emulator's saves and your
Companion session go to your other devices; one that was away catches up when you return to Ludolog.
Names, descriptions and genres edited in Ludolog travel too. With *Sharing* off nothing travels, but
what you play is still recorded and shared once it's back on.

**PC Link** is only for the PC app: while it's on, the PC can find the device and send it ROMs, art
and settings. It turns itself off after the *PC Link auto-off* time without PC activity. It can also
be switched from a quick settings tile and from the notification.

## Saves and backups

- Choose each emulator's saves folder with *Add emulator…* and *Set folder…*. Others are left alone.
- The newest save is the one from the device where the game was played last, as Ludolog recorded it,
  never by file dates. If that can't decide, nothing is overwritten: you pick the version to keep and
  the other is backed up.
- With *Check before playing*, Ludolog gets the newest save from your other devices before a game
  starts.
- Each emulator is backed up hourly, daily or weekly (you choose how many to keep), and before
  anything is replaced. Over the *Backup space* limit, the oldest go first. *Restore…* backs up the
  current saves first, so it can be undone.
- *Send this device's* pushes this device's saves to the others without asking.

If a card says *Emulator not supported*, see [limitations](limitations.md).

## Keep listening with the screen off

Link runs as a foreground service, so Android keeps it connected while the device sleeps. Still:

- **Battery: unrestricted** (recommended) lets Link restart on its own if something stops it;
  otherwise Ludolog wakes it when you return to it.
- **Process cleaner**: some handhelds close apps when the screen turns off. Add Ludolog Link to its
  exceptions in the device's settings. Link checks this and warns you.
- **Autostart**: some phones block apps from starting on their own. Allow it in Link's app settings.

## The PC app

The first launch after installing or updating takes a few extra seconds: Link prepares itself to
start faster from then on. If you use the portable copy, keep it in a folder you can write to (not
*Program Files*), or every launch will be a little slower.

Turn on PC Link on the device and click *Search*. Each connected device has these tabs:

- **Overview**: the device and *Ludolog data on this PC*, a copy of its settings, logbooks, game edits
  and consoles, refreshed when you connect. *Back up now…* makes an *Essential* or *Complete* backup
  (with art, videos, catalog and themes). *Restore…* saves the device's current data first, then
  Ludolog restarts to apply it; restoring logbooks loses anything played since the backup.
- **Games**: every game on every connected device and in the PC catalog, with filters for console,
  device, *Missing* files or art, and *Conflicts*. Click a game to copy, download, rename or delete
  it, fix or scrape its art, or *Edit* its name, description and genre on every device. Drop files on
  the window to upload them to the selected device.
- **Consoles**: set a console's name and description on every device that has it.
- **Saves**: save backups copied from the device; restore any version (close the game first).
- **Companion**, with its statistics, and the **Log**.
- **Settings**: *This PC* (name, folders, PC catalog), *Console* (Ludolog's settings for the device,
  sent with *Sync to device*) and *About*.

The **PC catalog** is a folder of games on the PC, one folder per console, set in *Settings → This
PC*. Deleting a game from a device is permanent. If you quit during transfers, downloads resume later
and partial uploads are discarded.

## About and diagnostics

*About*, at the end of Settings on both apps, shows the version and whether a newer one is out (it
only tells you). *Export diagnostics* saves a zip to attach to a bug report; check it before sharing,
as it can contain folder paths.
