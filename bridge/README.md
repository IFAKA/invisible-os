# Invisible bridge

## Quick start
On the Mac, as the user to add (once per Mac user and once per computer):
```
curl -fsSL https://raw.githubusercontent.com/IFAKA/invisible-os/master/install.sh | sh
```
Scan the QR code with the phone camera. The terminal says "Paired". Done. (No app on the phone yet? The QR page installs it first, then Connect.)

To take it all away again: `invisible uninstall` (asks once, then removes the hooks it added, the launchd service, the log and the settings folder). Other commands: `invisible status`, `invisible logs`; run `invisible` alone to see them all.

Answer Claude Code from your earbud. Claude asks to run a command, or asks you a question: the phone speaks it, you click to choose.

## How it works (a small picture)
The laptop is a shop. Claude Code is a worker who sometimes needs a yes or no. `hook.py` is the worker's note. `server.py` is the mail desk. The phone is you, with your eyes closed, listening to the desk.

- `server.py`: the mail desk. It waits for notes and for your answers.
- `hook.py`: Claude Code runs it at each permission, question, notification and stop. It sends a note and waits for your answer.
- If the desk is closed, or you do not answer in 10 minutes, `hook.py` says nothing. Claude Code then asks in the terminal as always. Nothing breaks.

## Set up (the whole story in 3 lines)
1. On the computer, as the Mac user to add, run the one line above (or `invisible pair`, or `sh bridge/setup.sh`; optional `--name Work`, `--port 8766`). It gets a copy into `~/invisible-os`, adds the Claude Code hooks to that user's `~/.claude/settings.json` (backup kept), starts the bridge at login (launchd, no terminal left open), puts the `invisible` command on PATH, and shows a QR code. Run it again any time: it changes nothing that is already right.
2. On the phone: scan the QR with the camera. It is a web link (`invisible-os.vercel.app/pair#CODE`, the code stays after `#`, so the site never sees it): with the app installed it opens the app and adds the computer; without it the page says "1. Download the app, 2. Connect". No camera? The code is in the Mac's clipboard: Computers, Add a computer, Paste.
3. The phone listens to the computer you scanned last. Switch with "Switch to Work" in the Laptop tool, or tap the computer under Computers.

Each Mac user gets their own copy, bridge and port (the next free one from 8765), named by the person's first name ("Facundo"), short to hear. The `invisible` command in `/opt/homebrew/bin` is shared by all users of the Mac and runs each user's own copy. The pair code carries the name, address and token, so there is nothing to type. Not built yet: scanning inside the app; the camera app does the scan.

## By hand (what setup.sh does for you)
- `./install.sh` prints the hooks text and a launchd file; it changes nothing itself.
- `python3 server.py` prints the address, the token and the pair code line (token in `~/.config/invisible-bridge/token`, mode 600; `--show-token` prints them again; `--name Work`, `--port 8766` or `INVISIBLE_BRIDGE_PORT`).
- Phone: Computers, Add a computer, paste the pair code ("Enter by hand" still takes the address and token).
- `hook.py` finds its bridge in `~/.config/invisible-bridge/url`, which `server.py` writes in that user's home, so each Mac user's hooks call their own port.

## More than one computer
You use one computer at a time; each computer or Mac user runs its own bridge with its own token. The phone listens to ONE computer, the active one, and does not hear the others until you switch.

The phone and the laptop must reach each other (same Wi-Fi, or Tailscale). Anyone with the token can answer for you, so keep it private.

## What you hear
- A permission: "demo asks: run npm test." Then: Allow, Deny, Always allow, Read again. Click when you hear the one you want.
- A question from Claude: the question, then each answer, then "Ask on the laptop".
- Done or needs you: one short line.

## Notes
- A question with several answers allowed (multi select) takes one answer from the phone.
- "Always allow" saves a rule in the project's local settings (Claude's own suggestion when it gives one).
- Settings: `INVISIBLE_BRIDGE_PORT` (server), `INVISIBLE_BRIDGE_URL`, `INVISIBLE_BRIDGE_TIMEOUT` (hook).

## Tests
`python3 -m unittest -v` in this folder (also `test_cli.py`: the `invisible` command with a temp HOME and fake launchctl, tmux and pbcopy, including the paired marker and uninstall;  `test_setup.py`: setup.sh with a temp HOME and a fake launchctl; `test_qr.py`: the QR encoder, checked by a small decoder and by Apple's reader on a Mac). It starts a server, a fake phone and the hook, and checks Allow, Deny, Always allow, questions, timeout and bridge down. `fake_phone.py URL TOKEN [choice]` plays the phone by hand.
