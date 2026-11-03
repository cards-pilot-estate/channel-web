# WG.10 evidence: customer journeys and failure cases

- Date: 2026-11-03T19:50Z
- channel-web: 2442bff plus this change; api-gateway with PR #8 (backend 429s pass through).
- Cluster: kind `cards-pilot` (dev). Chain: Playwright (Pixel 7 viewport) → Traefik → channel-web → API gateway → otp-service and onboarding-service.
- Command: `cd e2e && E2E_DISRUPTIVE=1 npx playwright test`. The disruptive case scales `onboarding-service` to 0 for about 20 s, then back.
- Accessibility: axe with tags wcag2a, wcag2aa, wcag21a, wcag21aa and wcag22aa on every page visited; any violation fails the test.

## Result

```
  ✓   1 [mobile] › tests/failures.spec.ts:8:1 › OTP lockout: wrong codes count down, the third voids the code (1.8s)
  ✓   2 [mobile] › tests/failures.spec.ts:30:1 › 429: a new code too soon says when to try again (807ms)
  ✓   3 [mobile] › tests/failures.spec.ts:41:1 › duplicate: a second application for a PAN in flight is refused politely (4.3s)
  ✓   4 [mobile] › tests/failures.spec.ts:59:1 › timeout: a warning at 13 minutes, then signed out with answers kept (1.2s)
  ✓   5 [mobile] › tests/failures.spec.ts:77:1 › platform down: submitting says the service is busy and keeps the answers (18.4s)
  ✓   6 [mobile] › tests/journey.spec.ts:14:3 › P01 approval: a customer applies on the web and sees the outcome (12.8s)
  ✓   7 [mobile] › tests/journey.spec.ts:14:3 › P03 decline on debt-to-income: a customer applies on the web and sees the outcome (8.0s)
  ✓   8 [mobile] › tests/journey.spec.ts:14:3 › P05 thin-file referral: a customer applies on the web and sees the outcome (7.7s)
  ✓   9 [mobile] › tests/journey.spec.ts:88:1 › validation errors are summarised and linked to the fields (442ms)
  ✓  10 [mobile] › tests/journey.spec.ts:99:1 › the service works in Hindi (463ms)
  10 passed (59.5s)
```

## What each case shows the customer

| Case | Expected | Seen |
| --- | --- | --- |
| OTP lockout | 2 and 1 tries left, then "send a new code"; the right code no longer works | as expected |
| 429 (new code within 30 s) | "Too many attempts. You can try again in N seconds", where N is otp-service's own wait | as expected, after api-gateway PR #8 |
| Duplicate (PAN in flight) | "You already have an application in progress" | as expected |
| Timeout | A dialog at 13 minutes (Stay signed in); signed out at 15 with "answers saved for 30 days"; `/apply` then asks to sign in | as expected (Playwright clock) |
| Platform down | "busy" or "not available" on submit; answers kept; submitting again after recovery gives "Application received" | as expected |

## Found while running

- The gateway rewrote backend 429s into its own (fixed in api-gateway PR #8).
- otp-service accepts a resend for an already-verified challenge. Harmless (the code cannot be used again), but it should be 404. Recorded for sim-comms.

## Not automated

- A screen-reader pass (NVDA or VoiceOver) and a 200% zoom pass: manual, still to do.
- Hindi legal copy review.
