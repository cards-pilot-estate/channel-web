# Working on this repository (assisted sessions)

Read first: `README.md`, then in the platform repository `docs/channels/web-channel.md` and `docs/channels/README.md` (rulings).

## Rules
- One issue, one branch `<type>/<issue#>-<slug>`, one PR `type(scope): summary` with `Closes #<issue>`; squash-merge after CI is green; record the PR in the platform's `docs/bank-grade/next-steps.md` § MG.
- The BFF calls the platform and otp-service **only through the API gateway**, with requests built from scratch: never forward a browser header or return an upstream body.
- The channel holds no internal-status mapping: customer statuses come from the platform's `next-action`.
- Personal data: drafts encrypted at rest; logs and metrics carry ids only (never a number, PAN, name, code).
- Nothing is stored before the processing and bureau consents.
- No secrets, no real bank or vendor names (the brand is the fictional "Ondaro Bank").

## Commands
`make verify` · `make load` · `make deploy ENV=dev` · `bash scripts/journey-test.sh dev`. JDK 25 to build (target 21); `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` on Colima.
