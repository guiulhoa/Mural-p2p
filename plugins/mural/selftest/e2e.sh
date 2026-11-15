#!/usr/bin/env bash
# Teste ponta a ponta do mural com o núcleo REAL do Pitiupi, sem interface gráfica.
#
# Sobe dois processos Java (Ana e Bia), cada um num namespace de rede Linux próprio,
# ligados por um cabo virtual (veth). Assim cada um tem seu IP e dá para exercitar
# multicast, unicast e TCP de verdade (duas instâncias no mesmo IP/porta se atrapalham).
#
# Requer: Linux, root, iproute2, JDK 17+ e `ant selftest` já executado (gera os .class).
# Uso:    sudo ./selftest/e2e.sh
set -u
cd "$(dirname "$0")/.."

CORE=../../pitiupi/dist/pitiupi.jar
JAR=dist/pitiupi.mural.jar
CP="$CORE:build/selftest"
PORT=5000
WORK=$(mktemp -d /tmp/mural-e2e.XXXXXX)
fail=0

[ -f "$JAR" ] && [ -f "$CORE" ] && [ -d build/selftest ] || { echo "Rode 'ant selftest' antes."; exit 1; }

cleanup() {
  ip netns del mural_a 2>/dev/null; ip netns del mural_b 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT
cleanup_ns() { ip netns del mural_a 2>/dev/null; ip netns del mural_b 2>/dev/null; }

cleanup_ns
ip netns add mural_a && ip netns add mural_b || exit 1
ip link add va type veth peer name vb
ip link set va netns mural_a; ip link set vb netns mural_b
ip netns exec mural_a sh -c 'ip addr add 10.77.0.1/24 dev va; ip link set va up; ip link set lo up; ip link set va multicast on; ip route add 224.0.0.0/4 dev va'
ip netns exec mural_b sh -c 'ip addr add 10.77.0.2/24 dev vb; ip link set vb up; ip link set lo up; ip link set vb multicast on; ip route add 224.0.0.0/4 dev vb'

for who in a b; do
  mkdir -p "$WORK/$who/plugins"
  cp "$JAR" "$WORK/$who/plugins/mural.jar"
done

run_case() {
  local destino=$1 modo=$2
  echo "=== $destino / $modo ==="
  ip netns exec mural_b env PITIUPI_HOME="$WORK/b" java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
      -cp "$CP" pitiupi.mural.E2eNode reader Bia $PORT > "$WORK/b.log" 2>&1 &
  local pb=$!
  sleep 2
  ip netns exec mural_a env PITIUPI_HOME="$WORK/a" timeout 90 java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
      -cp "$CP" pitiupi.mural.E2eNode sender Ana $PORT "$destino" "$modo" > "$WORK/a.log" 2>&1
  local ra=$?
  wait $pb; local rb=$?
  sed 's/^/  /' "$WORK/a.log" | grep -v "^  \[Ana\] status: Novo" ; sed 's/^/  /' "$WORK/b.log"
  if [ $ra -eq 0 ] && [ $rb -eq 0 ]; then echo "  -> OK"; else echo "  -> FALHOU (ana=$ra bia=$rb)"; fail=1; fi
}

run_case SALA   MULTICAST_UDP
run_case SALA   UNICAST_UDP
run_case SALA   TCP
run_case PESSOA UNICAST_UDP
run_case PESSOA TCP

[ $fail -eq 0 ] && echo "TODOS OS CENARIOS PASSARAM" || echo "HOUVE FALHAS"
exit $fail
