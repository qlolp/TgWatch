#!/usr/bin/env bash
# Actual broker, test-only CA, independently observed payload. No production trust changes.
set -euo pipefail
cd "$(dirname "$0")/.."
image='eclipse-mosquitto:2.0.22@sha256:199ea8ef2e35ec2b1b37e59cfd1dbae538ed4dfa4a2251a121a52215a6248a21'
mkdir -p build/mqtt-tls
fixture=$(mktemp -d "${TMPDIR:-/tmp}/tgwatch-mqtt-tls.XXXXXXXX")
container="tgwatch-mqtt-$(basename "$fixture" | tr '[:upper:]' '[:lower:]')"
subscriber=''
cleanup() {
  if [[ -n "$subscriber" ]]; then kill "$subscriber" 2>/dev/null || true; wait "$subscriber" 2>/dev/null || true; fi
  docker logs "$container" > build/mqtt-tls/broker.log 2>&1 || true
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf -- "$fixture"
}
trap cleanup EXIT
for name in trusted untrusted; do
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -sha256 \
    -subj "/CN=TgWatch CI $name CA" -keyout "$fixture/$name-ca.key" -out "$fixture/$name-ca.crt" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -subj '/CN=localhost' \
    -keyout "$fixture/$name.key" -out "$fixture/$name.csr" 2>/dev/null
  printf 'subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n' > "$fixture/extensions"
  openssl x509 -req -days 1 -sha256 -in "$fixture/$name.csr" -CA "$fixture/$name-ca.crt" \
    -CAkey "$fixture/$name-ca.key" -CAcreateserial -extfile "$fixture/extensions" -out "$fixture/$name.crt" 2>/dev/null
done
keytool -importcert -noprompt -storetype JKS -alias tgwatch-fixture -storepass fixture-password \
  -keystore "$fixture/truststore.jks" -file "$fixture/trusted-ca.crt"
docker pull "$image"
docker run --rm --user "$(id -u):$(id -g)" --entrypoint mosquitto_passwd \
  -v "$fixture:/fixture" "$image" -b -c /fixture/passwords canary fixture-password
cat > "$fixture/mosquitto.conf" <<'CONFIG'
per_listener_settings true
log_dest stdout
log_type all
listener 8883 0.0.0.0
certfile /fixture/trusted.crt
keyfile /fixture/trusted.key
allow_anonymous false
password_file /fixture/passwords
listener 8884 0.0.0.0
certfile /fixture/untrusted.crt
keyfile /fixture/untrusted.key
allow_anonymous false
password_file /fixture/passwords
CONFIG
docker run -d --name "$container" --user "$(id -u):$(id -g)" --entrypoint mosquitto \
  -p 127.0.0.1:18883:8883 -p 127.0.0.1:18884:8884 -v "$fixture:/fixture:ro" "$image" -c /fixture/mosquitto.conf
for port in 8883 8884; do
  ca=trusted; [[ "$port" == 8884 ]] && ca=untrusted
  ready=false
  for attempt in {1..20}; do
    if python3 - "$container" "$port" "$ca" <<'READY'
import subprocess, sys
try:
    result = subprocess.run(['docker', 'exec', sys.argv[1], 'mosquitto_pub', '-h', 'localhost',
        '-p', sys.argv[2], '--cafile', '/fixture/' + sys.argv[3] + '-ca.crt', '-u', 'canary',
        '-P', 'fixture-password', '-t', 'tgwatch/ci/readiness', '-m', 'ready', '-q', '1'], timeout=3)
except subprocess.TimeoutExpired:
    raise SystemExit(1)
raise SystemExit(result.returncode)
READY
    then ready=true; break; fi
    sleep .5
  done
  [[ "$ready" == true ]] || { echo 'TLS fixture did not become ready' >&2; exit 1; }
done
bash ./gradlew :app:compileDebugUnitTestSources --no-daemon
docker exec -t "$container" mosquitto_sub -h localhost -p 8883 --cafile /fixture/trusted-ca.crt \
  -u canary -P fixture-password -t tgwatch/ci/canary -q 1 -C 1 -W 120 -d \
  > build/mqtt-tls/subscriber.log 2>&1 &
subscriber=$!
python3 - <<'PY'
from pathlib import Path
import time
log=Path('build/mqtt-tls/subscriber.log')
deadline=time.monotonic()+15
while time.monotonic()<deadline:
    if 'Subscribed (mid:' in log.read_text(): break
    time.sleep(.1)
else: raise SystemExit('Independent MQTT subscriber did not confirm subscription')
PY
TGWATCH_MQTT_FIXTURE="$fixture" bash ./gradlew :app:mqttTlsIntegrationTest --no-daemon --stacktrace
wait "$subscriber"
subscriber=''
python3 - <<'PY'
import json
from pathlib import Path
from xml.etree import ElementTree
results=list(Path('app/build/test-results/mqttTlsIntegrationTest').glob('TEST-*.xml'))
assert results, 'No actual MQTT TLS test report'
cases=[case for report in results for case in ElementTree.parse(report).getroot().iter('testcase')]
assert len(cases)==4 and all(not list(case) or all(x.tag=='system-out' for x in case) for case in cases), 'Missing, failed or skipped MQTT test'
lines=Path('build/mqtt-tls/subscriber.log').read_text().splitlines()
assert lines.count('tgwatch-tls-canary-v1')==1, 'Independent subscriber did not receive the exact canary payload'
Path('build/mqtt-tls/evidence.json').write_text(json.dumps({'broker':'eclipse-mosquitto:2.0.22','tests':len(cases),'skipped':0,'independentExactPayload':True,'testOnlyTrustStore':True},indent=2)+'\n')
PY
