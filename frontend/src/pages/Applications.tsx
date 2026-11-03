import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api, ApiError, type Status } from '../api'
import { Button, ErrorSummary, Page, TextField } from '../components/forms'
import { useSession } from '../context'
import { useT } from '../i18n'
import { navigate } from '../router'

const FINAL = new Set(['APPROVED', 'DECLINED', 'WITHDRAWN', 'CLOSED', 'UNDER_REVIEW'])

/** S20/S21: honest async status. Polls 3, 5, 10, 30 s (from the BFF), stops after about 2 minutes. */
export function Submitted({ id }: { id: string }) {
  const t = useT()
  const ref = sessionStorage.getItem('cw.ref')
  return (
    <Page title={t('submitted.title')}>
      <div className="panel">
        <p>{t('submitted.ref')}</p>
        <p className="panel-ref">{ref}</p>
      </div>
      <h2 className="heading-m">{t('submitted.next')}</h2>
      <p>{t('submitted.nextText')}</p>
      <ApplicationStatus id={id} />
    </Page>
  )
}

export function ApplicationStatus({ id }: { id: string }) {
  const t = useT()
  const { session } = useSession()
  const [status, setStatus] = useState<Status | null>(null)
  const [stopped, setStopped] = useState(false)
  const [message, setMessage] = useState<string>()
  const [withdrawing, setWithdrawing] = useState(false)
  const timer = useRef<number | undefined>(undefined)

  function poll(step = 0, started = Date.now()) {
    const schedule = session?.polling.scheduleSeconds ?? [3, 5, 10, 30]
    const max = (session?.polling.maxSeconds ?? 120) * 1000
    api<Status>('GET', `/bff/applications/${id}/status`).then(({ data }) => {
      setStatus(data)
      if (FINAL.has(data.customerStatus)) return
      if (Date.now() - started > max) { setStopped(true); return }
      timer.current = window.setTimeout(() => poll(step + 1, started), schedule[Math.min(step, schedule.length - 1)] * 1000)
    }).catch((e: ApiError) => {
      if (e.status === 401) { navigate('/signed-out'); return }
      const wait = e.retryAfterSeconds ?? schedule[Math.min(step, schedule.length - 1)]
      timer.current = window.setTimeout(() => poll(step + 1, started), wait * 1000)
    })
  }

  useEffect(() => {
    poll()
    return () => window.clearTimeout(timer.current)
  }, [id])

  return (
    <div className="status-block">
      <ErrorSummary errors={{}} message={message} />
      <div role="status" aria-live="polite">
        {status ? (
          <>
            <p><strong className={'tag status-' + status.customerStatus}>{t('status.' + status.customerStatus)}</strong></p>
            <p>{t('status.' + status.customerStatus + '.text')}</p>
          </>
        ) : <p>{t('apps.checking')}</p>}
        {stopped && <p>{t('apps.stopped')}</p>}
      </div>
      {(stopped || (status && FINAL.has(status.customerStatus))) && (
        <Button type="button" secondary onClick={() => { setStopped(false); poll() }}>{t('apps.checkAgain')}</Button>
      )}
      {status?.canWithdraw && !withdrawing && (
        <p><a href="#" onClick={(e) => { e.preventDefault(); setWithdrawing(true) }}>{t('apps.withdraw')}</a></p>
      )}
      {withdrawing && <Withdraw id={id} onDone={(s) => { setStatus(s); setWithdrawing(false) }} onError={setMessage} />}
    </div>
  )
}

function Withdraw({ id, onDone, onError }: { id: string; onDone: (s: Status) => void; onError: (m: string) => void }) {
  const t = useT()
  const [sent, setSent] = useState(false)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string>()

  useEffect(() => {
    api('POST', '/bff/auth/step-up/send').then(() => setSent(true)).catch(() => onError(t('error.unavailable')))
  }, [])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(undefined)
    try {
      await api('POST', '/bff/auth/step-up/verify', { code: code.replace(/\s/g, '') })
      const { data } = await api<Status>('POST', `/bff/applications/${id}/withdrawal`)
      onDone(data)
    } catch (err) {
      const ae = err as ApiError
      if (ae.status === 422) setError(t('code.wrong', { n: ae.attemptsRemaining ?? 0 }))
      else if (ae.status === 410) setError(t('code.expired'))
      else onError(t('error.generic'))
    }
  }

  if (!sent) return <p role="status">…</p>
  return (
    <form onSubmit={submit} noValidate className="inset">
      <p>{t('apps.withdrawConfirm')}</p>
      <TextField id="stepUpCode" label={t('code.label')} value={code} onChange={setCode} error={error}
        inputMode="numeric" autoComplete="one-time-code" width="short" />
      <Button>{t('apps.withdraw')}</Button>
    </form>
  )
}

type Listing = { draft?: { updatedAt: string }; applications: { applicationId: string; applicationRef: string; product: string; customerStatus: string }[] }

export function Applications({ id }: { id?: string }) {
  const t = useT()
  const [list, setList] = useState<Listing | null>(null)

  useEffect(() => {
    api<Listing>('GET', '/bff/applications').then(({ data }) => setList(data))
      .catch((e: ApiError) => { if (e.status === 401) navigate('/sign-in?next=/applications', true) })
  }, [id])

  if (id) return <Page title={t('apps.title')} back={() => navigate('/applications')}><ApplicationStatus id={id} /></Page>
  if (!list) return <p role="status">…</p>
  return (
    <Page title={t('apps.title')}>
      {list.draft && (
        <p><a href="/apply" onClick={(e) => { e.preventDefault(); navigate('/apply') }}>{t('apps.continueDraft')}</a></p>
      )}
      {list.applications.length === 0 ? <p>{t('apps.none')}</p> : (
        <ul className="app-list">
          {list.applications.map((a) => (
            <li key={a.applicationId}>
              <a href={'/applications/' + a.applicationId} onClick={(e) => { e.preventDefault(); navigate('/applications/' + a.applicationId) }}>
                {a.applicationRef}
              </a>{' '}
              <strong className={'tag status-' + a.customerStatus}>{t('status.' + a.customerStatus)}</strong>
            </li>
          ))}
        </ul>
      )}
      {!list.draft && (
        <p><a href="/privacy-notice" onClick={(e) => { e.preventDefault(); navigate('/privacy-notice') }}>{t('apps.startNew')}</a></p>
      )}
    </Page>
  )
}
