#!/bin/sh
# One line for a new Mac user:  curl -fsSL https://raw.githubusercontent.com/IFAKA/invisible-os/master/install.sh | sh
# Gets Invisible OS into ~/invisible-os (or updates it), puts the `invisible` command on PATH, then pairs your phone.
# Safe to run again. Remove everything later with: invisible uninstall
set -e
DIR="${INVISIBLE_HOME:-$HOME/invisible-os}"
SRC="${INVISIBLE_SOURCE:-https://github.com/IFAKA/invisible-os}"
command -v git >/dev/null || { echo "Needs git. Run: xcode-select --install   then run this line again."; exit 1; }
command -v python3 >/dev/null || { echo "Needs python3. Run: xcode-select --install   then run this line again."; exit 1; }
if [ -d "$DIR/.git" ]; then
  echo "Updating Invisible OS in $DIR"
  git -C "$DIR" pull -q --ff-only "$SRC" master || echo "(could not update; using the copy you have)"
else
  echo "Getting Invisible OS into $DIR"
  git clone -q -b master "$SRC" "$DIR"
fi
# curl | sh gives the script on stdin: give `pair` the keyboard back
if [ -r /dev/tty ] && [ -z "$INVISIBLE_NO_TTY" ]; then exec python3 "$DIR/bin/invisible" pair "$@" </dev/tty; fi
exec python3 "$DIR/bin/invisible" pair "$@"
