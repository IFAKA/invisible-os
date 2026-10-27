#!/bin/sh
# Play a brand-new Mac user running the README's one line, without touching your real setup.
#   tools/new_user_sim.sh [SOURCE]   (SOURCE: a git url or bundle; default: this checkout's committed HEAD)
# Fresh HOME in /tmp, no `invisible` on PATH, launchd and pbcopy faked (the bridge runs with the fake HOME,
# so your real token and launchd job are untouched). Log: /tmp/invisible-newuser/log.
# Afterwards: tools/new_user_sim.sh --stop  (stops the practice bridge)
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIM=/tmp/invisible-newuser
if [ "$1" = "--stop" ]; then
  [ -f $SIM/bridge.pid ] && kill "$(cat $SIM/bridge.pid)" 2>/dev/null && echo "stopped practice bridge"
  exit 0
fi
[ -f $SIM/bridge.pid ] && kill "$(cat $SIM/bridge.pid)" 2>/dev/null || true
rm -rf $SIM; mkdir -p $SIM/home $SIM/fakebin
SRC="${1:-$SIM/src.git}"
if [ -z "$1" ]; then git clone -q --bare "$ROOT" $SIM/src.git && git -C $SIM/src.git branch -f master "$(git -C "$ROOT" rev-parse HEAD)"; fi
cat > $SIM/fakebin/launchctl <<EOF
#!/bin/sh
# fake launchctl: bootstrap runs the plist's program with the fake HOME
case "\$1" in
  print) [ -f $SIM/bridge.pid ] && kill -0 "\$(cat $SIM/bridge.pid)" 2>/dev/null ;;
  bootout|unload) [ -f $SIM/bridge.pid ] && kill "\$(cat $SIM/bridge.pid)" 2>/dev/null; rm -f $SIM/bridge.pid; exit 0 ;;
  bootstrap|load) p="\$3"; [ "\$1" = load ] && p="\$3"
    for a in "\$@"; do case "\$a" in *.plist) p="\$a";; esac; done
    /usr/bin/python3 -c 'import plistlib,sys,subprocess,os; d=plistlib.load(open(sys.argv[1],"rb")); log=open(d.get("StandardErrorPath","/dev/null"),"a"); print(subprocess.Popen(d["ProgramArguments"],stdout=log,stderr=log,start_new_session=True).pid)' "\$p" > $SIM/bridge.pid ;;
esac
EOF
printf '#!/bin/sh\ncat > %s/clipboard\n' $SIM > $SIM/fakebin/pbcopy
chmod +x $SIM/fakebin/*
# PATH: fake tools first, then the real ones, minus any `invisible` already installed
P="$SIM/fakebin"
for d in $(echo "$PATH" | tr ':' ' '); do [ -e "$d/invisible" ] && continue; P="$P:$d"; done
for t in tmux claude; do c="$(command -v $t || true)"; [ -n "$c" ] && ln -sf "$c" $SIM/fakebin/$t; done
cd $SIM/home
env -i HOME=$SIM/home PATH="$P" TERM="${TERM:-xterm}" USER="$USER" LOGNAME="$USER" \
  INVISIBLE_SOURCE="$SRC" INVISIBLE_BIN_DIR=$SIM/home/.local/bin INVISIBLE_NO_TTY=1 \
  sh "$ROOT/install.sh" 2>&1 | tee $SIM/log
