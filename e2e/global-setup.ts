import { spawn } from 'node:child_process'

// Port-forwards for the two test-only doors: otp-service's last-code hook and the bureau
// simulator's persona pins. Neither is routed by the gateway.
export default async function globalSetup() {
  const env = process.env.E2E_ENV ?? 'dev'
  const forwards = [
    spawn('kubectl', ['-n', `comms-${env}`, 'port-forward', 'svc/otp-service', '18095:8080'], { stdio: 'ignore' }),
    spawn('kubectl', ['-n', `cards-${env}`, 'port-forward', 'svc/bank-simulators', '18096:8081'], { stdio: 'ignore' }),
  ]
  await new Promise((r) => setTimeout(r, 2500))
  return async () => forwards.forEach((p) => p.kill())
}
