#!/bin/zsh
# Uso: tools/gbuild.sh <tarefas gradle...>
# Roda ./gradlew com trava exclusiva: vários devs compilam em paralelo, os builds esperam a vez.
P=${0:a:h:h}
L=$P/.gbuild.lock
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
until mkdir $L 2>/dev/null; do
  [[ -f $L/pid ]] && ! kill -0 $(cat $L/pid) 2>/dev/null && rm -rf $L && continue
  sleep 3
done
echo $$ > $L/pid
trap 'rm -rf $L' EXIT INT TERM
cd $P && ./gradlew --console=plain -q "$@" 2>&1 | grep -v "SDK XML"
exit ${pipestatus[1]}
