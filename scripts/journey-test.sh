#!/usr/bin/env bash
# Live journey through the real chain: browser -> Traefik -> channel-web BFF -> API gateway ->
# otp-service and onboarding-service. Acts like a browser (cookie jar, CSRF header). Reads the code
# from otp-service's test hook (dev only) through a port-forward, as a tester reads the simulated phone.
set -uo pipefail
ENV="${1:-dev}"
WEB="http://web.$ENV.localtest.me"
JAR=$(mktemp); trap 'rm -f "$JAR"; kill $PF 2>/dev/null' EXIT
MOBILE="9$(printf '%09d' $((RANDOM * 30000 + RANDOM)))"
PAN="ABCPJ$(printf '%04d' $((RANDOM % 10000)))Q"
FAILED=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1 ($3)"; else echo "FAIL  $1: expected $2, got $3"; FAILED=1; fi; }
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -1; }
req() {  # method path [json] -> prints body, then status on the last line
  local m=$1 p=$2 d=${3:-}
  if [ -n "$d" ]; then curl -s -b "$JAR" -c "$JAR" -X "$m" "$WEB$p" -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' \
      ${IFMATCH:+-H "If-Match: $IFMATCH"} -d "$d" -w '\n%{http_code}'
  else curl -s -b "$JAR" -c "$JAR" -X "$m" "$WEB$p" -H "X-XSRF-TOKEN: $(xsrf)" -w '\n%{http_code}'; fi
}
code() { tail -1 <<<"$1"; }
field() { sed '$d' <<<"$1" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d$2)" 2>/dev/null; }

kubectl -n "comms-$ENV" port-forward svc/otp-service 18092:8080 >/dev/null 2>&1 & PF=$!
sleep 2

R=$(req GET /bff/session); check "the session starts anonymous" 200 "$(code "$R")"
check "the page is served" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/")"
R=$(req POST /bff/auth/otp/send "{\"mobile\":\"$MOBILE\"}"); check "a code is sent through the gateway" 202 "$(code "$R")"
OTP=$(curl -s "http://127.0.0.1:18092/sim-admin/v1/test/last-code?msisdn=%2B91$MOBILE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["code"])')
R=$(req POST /bff/auth/otp/verify '{"code":"000000"}'); [ "$OTP" = 000000 ] || check "a wrong code is refused" 422 "$(code "$R")"
R=$(req POST /bff/auth/otp/verify "{\"code\":\"$OTP\"}"); check "the right code signs the customer in" 200 "$(code "$R")"
R=$(req POST /bff/drafts '{"noticeVersion":"PN-2026.1","locale":"en-IN","purposes":{"PROCESSING":true,"BUREAU_CHECK":true,"MARKETING":false}}')
check "the consents start a draft" 201 "$(code "$R")"
V=$(field "$R" "['rowVersion']")
for s in "about-you|{\"fullName\":\"Journey Test\",\"pan\":\"$PAN\",\"dateOfBirth\":\"1990-04-12\",\"email\":\"journey@example.com\"}" \
         'address|{"addressLine1":"12 Residency Road","city":"Bengaluru","state":"Karnataka","pincode":"560025"}' \
         'work|{"employmentType":"SALARIED","employerName":"Acme Logistics","monthlyIncome":"150000","monthlyObligation":"20000"}' \
         'card|{"cardType":"GOLD","kfsAcknowledged":true}'; do
  IFMATCH=$V R=$(req PUT "/bff/drafts/current/sections/${s%%|*}" "${s#*|}")
  check "section ${s%%|*} is saved" 200 "$(code "$R")"; V=$(field "$R" "['rowVersion']")
done
R=$(req POST /bff/drafts/current/submission); check "the application is submitted through the gateway" 202 "$(code "$R")"
APP=$(field "$R" "['applicationId']"); echo "      reference $(field "$R" "['applicationRef']")"
STATUS=""; for wait in 3 5 10 30; do
  sleep "$wait"; R=$(req GET "/bff/applications/$APP/status"); STATUS=$(field "$R" "['customerStatus']")
  case "$STATUS" in APPROVED|DECLINED|UNDER_REVIEW|CLOSED) break ;; esac
done
outcome() { case "$1" in APPROVED|DECLINED|UNDER_REVIEW|CLOSED) echo yes ;; *) echo "${1:-none}" ;; esac; }
check "the status reaches an outcome the customer can act on" yes "$(outcome "$STATUS")"
echo "      customer status: $STATUS"
R=$(req GET /bff/applications); check "the application is listed for its owner" 1 "$(field "$R" "['applications'].__len__()")"

# Another customer cannot see it.
rm -f "$JAR"; JAR2=$(mktemp); JAR=$JAR2
MOBILE="8$(printf '%09d' $((RANDOM * 30000 + RANDOM)))"
req GET /bff/session >/dev/null; req POST /bff/auth/otp/send "{\"mobile\":\"$MOBILE\"}" >/dev/null
OTP=$(curl -s "http://127.0.0.1:18092/sim-admin/v1/test/last-code?msisdn=%2B91$MOBILE" | python3 -c 'import sys,json; print(json.load(sys.stdin)["code"])')
req POST /bff/auth/otp/verify "{\"code\":\"$OTP\"}" >/dev/null
R=$(req GET "/bff/applications/$APP/status"); check "another customer gets not-found" 404 "$(code "$R")"
R=$(req POST /bff/logout); check "sign out" 200 "$(code "$R")"
R=$(req GET /bff/applications); check "after sign-out the API needs a session" 401 "$(code "$R")"

[ $FAILED = 0 ] && echo "Journey passed" || echo "Journey FAILED"
exit $FAILED
