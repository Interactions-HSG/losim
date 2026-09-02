#!/usr/bin/env bash
# Starts the two things this container promises, every time it is attached to.
#
# The container forwards 3000 and 8000 and opens a preview on 8000, and until
# now nothing ever bound them: the only lifecycle hook built losim and printed a
# list of commands, so the first thing anybody saw was a preview pane on a port
# with nothing behind it — while the code's own comments said "the devcontainer
# starts it; nobody types anything".
#
# Both are started in the background and this returns, because a lifecycle
# command that does not return is a container that never finishes attaching.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build

[ -f build/losim.jar ] || ./build.sh > /dev/null

# The viewer, from the committed export rather than from npm. It is committed
# like the generated protobuf sources are (D10), so putting it where the server
# looks for it is a copy and never a build.
if [ ! -f build/viewer/index.html ]; then
  rm -rf build/viewer
  cp -r viewer/out build/viewer
  mkdir -p build/viewer/traces
  echo '{"runs": []}' > build/viewer/traces/index.json
fi

# bash's own /dev/tcp, so this needs nothing installed. Attaching to a container
# that is already up must not start a second copy: losim would print "already
# running" and then park, leaving a JVM behind for every attach.
listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

start() {                                    # port  log  args...
  local port=$1 log=$2; shift 2
  if listening "$port"; then
    echo "  :$port  already up"
    return
  fi
  nohup java -cp build/losim.jar losim.cli.Main "$@" --port "$port" \
        > "build/$log" 2>&1 &
  echo "  :$port  starting  (build/$log)"
}

echo "losim:"
start 8000 serve.log serve --no-open
start 3000 manual.log serve docs
