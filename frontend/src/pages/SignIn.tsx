import { useState, type FormEvent } from 'react'
import { api, ApiError } from '../api'
import { Button, ErrorSummary, Page, TextField } from '../components/forms'
import { useSession } from '../context'
import { useT } from '../i18n'
import { navigate } from '../router'

export function Start() {
  const t = useT()
  return (
    <Page title={t('start.title')}>
      <p className="lead">{t('start.lead')}</p>
      <p>{t('start.need')}</p>
      <ul className="list-bullet">
        <li>{t('start.need.mobile')}</li>
        <li>{t('start.need.pan')}</li>
        <li>{t('start.need.income')}</li>
      </ul>
      <p>{t('start.eligibility')}</p>
      <a href="/sign-in" className="button start" role="button" draggable={false}
        onClick={(e) => { e.preventDefault(); navigate('/sign-in') }}>{t('start.button')} <span aria-hidden="true">→</span></a>
      <h2 className="heading-m">{t('start.existing')}</h2>
      <p><a href="/sign-in" onClick={(e) => { e.preventDefault(); navigate('/sign-in?next=/applications') }}>{t('start.track')}</a></p>
    </Page>
  )
}

function busy(t: ReturnType<typeof useT>, e: ApiError): string {
  if (e.status === 429) return t(e.code === 'OTP_RESEND_TOO_SOON' || e.code === 'OTP_SEND_LIMIT' || e.code === 'TOO_MANY_ATTEMPTS'
    ? 'code.tooMany' : 'error.busy', { s: e.retryAfterSeconds ?? 60 })
  if (e.status >= 500 || e.status === 0) return t('error.unavailable')
  return t('error.generic')
}

export function SignIn() {
  const t = useT()
  const [mobile, setMobile] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState<string>()
  const [sending, setSending] = useState(false)
  const next = new URLSearchParams(location.search).get('next') ?? '/privacy-notice'

  async function submit(e: FormEvent) {
    e.preventDefault()
    setErrors({}); setMessage(undefined)
    const digits = mobile.replace(/[\s()-]/g, '').replace(/^\+91/, '').replace(/^0/, '')
    if (!/^[6-9][0-9]{9}$/.test(digits)) {
      setErrors({ mobile: t('err.format', { f: t('f.mobile').toLowerCase() }) })
      return
    }
    setSending(true)
    try {
      const { data } = await api<{ maskedMobile: string }>('POST', '/bff/auth/otp/send', { mobile: digits })
      sessionStorage.setItem('cw.masked', data.maskedMobile)
      navigate('/sign-in/code?next=' + encodeURIComponent(next))
    } catch (err) {
      const ae = err as ApiError
      if (ae.code === 'VALIDATION_FAILED') setErrors({ mobile: t('err.format', { f: t('f.mobile').toLowerCase() }) })
      else setMessage(busy(t, ae))
    } finally {
      setSending(false)
    }
  }

  return (
    <Page title={t('signin.title')} back={() => navigate('/')}>
      <ErrorSummary errors={errors} message={message} />
      <form onSubmit={submit} noValidate>
        <TextField id="mobile" label={t('signin.label')} hint={t('signin.hint')} value={mobile} onChange={setMobile}
          error={errors.mobile} type="tel" inputMode="tel" autoComplete="tel-national" width="medium" />
        <Button disabled={sending}>{t('signin.send')}</Button>
      </form>
    </Page>
  )
}

export function Code() {
  const t = useT()
  const { refresh } = useSession()
  const [code, setCode] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const masked = sessionStorage.getItem('cw.masked') ?? ''
  const next = new URLSearchParams(location.search).get('next') ?? '/privacy-notice'

  async function submit(e: FormEvent) {
    e.preventDefault()
    setErrors({}); setMessage(undefined)
    const clean = code.replace(/\s/g, '')
    if (!/^[0-9]{6}$/.test(clean)) {
      setErrors({ code: t('err.format', { f: t('code.label').toLowerCase() }) })
      return
    }
    try {
      await api('POST', '/bff/auth/otp/verify', { code: clean })
      await refresh()
      navigate(next, true)
    } catch (err) {
      const ae = err as ApiError
      if (ae.status === 422) setErrors({ code: t('code.wrong', { n: ae.attemptsRemaining ?? 0 }) })
      else if (ae.status === 410 || ae.status === 409) setErrors({ code: t('code.expired') })
      else setMessage(busy(t, ae))
    }
  }

  async function resend() {
    setErrors({}); setMessage(undefined); setNotice(undefined)
    try {
      await api('POST', '/bff/auth/otp/resend')
      setNotice(t('code.resent'))
    } catch (err) {
      setMessage(busy(t, err as ApiError))
    }
  }

  return (
    <Page title={t('code.title')} back={() => navigate('/sign-in')}>
      <ErrorSummary errors={errors} message={message} />
      <p>{t('code.sent', { m: masked.slice(-4) })}</p>
      <form onSubmit={submit} noValidate>
        <TextField id="code" label={t('code.label')} hint={t('code.hint')} value={code} onChange={setCode} error={errors.code}
          inputMode="numeric" autoComplete="one-time-code" width="short" />
        <Button>{t('code.verify')}</Button>
      </form>
      <details className="details">
        <summary>{t('code.notReceived')}</summary>
        <div className="details-text">
          <Button type="button" secondary onClick={resend}>{t('code.resend')}</Button>
        </div>
      </details>
      <p role="status" className="notice">{notice}</p>
    </Page>
  )
}
