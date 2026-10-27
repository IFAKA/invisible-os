# Invisible bridge

Answer Claude Code from your earbud. Claude asks to run a command, or asks you a question: the phone speaks it, you click to choose.

## How it works (a small picture)
The laptop is a shop. Claude Code is a worker who sometimes needs a yes or no. `hook.py` is the worker's note. `server.py` is the mail desk. The phone is you, with your eyes closed, listening to the desk.

- `server.py`: the mail desk. It waits for notes and for your answers.
- `hook.py`: Claude Code runs it at each permission, question, notification and stop. It sends a note and waits for your answer.
- If the desk is closed, or you do not answer in 10 minutes, `hook.py` says nothing. Claude Code then asks in the terminal as always. Nothing breaks.

## Set up (3 steps)
1. Run `./install.sh`. It prints the text to add to `~/.claude/settings.json`. It changes nothing itself.
2. Run `python3 server.py`. It prints the address and a secret token. The token is saved in `~/.config/invisible-bridge/token` (only you can read it).
3. On the phone, open the app home screen, open "Computers", "Add a computer": type a name (Home, Work), the address and the token. Then in the earbud menu choose Laptop.

## More than one computer
Each computer (or each Mac user) runs its own `server.py`, with its own token. Two bridges on the same Mac need two ports: the first uses 8765, start the second with `python3 server.py --port 8766` (or `INVISIBLE_BRIDGE_PORT=8766`). Add each one in the app under "Computers" with its own name. The phone listens to all of them. With two or more computers it says the name first ("Work, site, done"), and the Laptop tool first asks which computer.

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
`python3 -m unittest -v` in this folder. It starts a server, a fake phone and the hook, and checks Allow, Deny, Always allow, questions, timeout and bridge down. `fake_phone.py URL TOKEN [choice]` plays the phone by hand.
