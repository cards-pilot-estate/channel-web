import { useEffect, useState } from 'react'
import { api, ApiError, type Draft } from '../api'
import { Button, ErrorSummary, Page } from '../components/forms'
import { useT } from '../i18n'
import { navigate } from '../router'
import { SECTIONS } from './Tasks'

const FIELDS: Record<string, string[]> = {
  card: ['cardType'],
  'about-you': ['fullName', 'pan', 'dateOfBirth', 'email'],
  address: ['addressLine1', 'addressLine2', 'city', 'state', 'pincode'],
  work: ['employmentType', 'employerName', 'monthlyIncome', 'monthlyObligation'],
}

/** S19: "Check answers" (WCAG 3.3.4 error prevention for a financial commitment), then submit. */
export function Check() {
  const t = useT()
  const [draft, setDraft] = useState<Draft | null>(null)
  const [message, setMessage] = useState<string>()
  const [sending, setSending] = useState(false)

  useEffect(() => {
    api<Draft>('GET', '/bff/drafts/current').then(({ data }) => {
      if (!data.complete) navigate('/apply', true)
      setDraft(data)
    }).catch((e: ApiError) => navigate(e.status === 401 ? '/signed-out' : '/applications', true))
  }, [])

  async function submit() {
    setSending(true); setMessage(undefined)
    try {
      const { data } = await api<{ applicationId: string; applicationRef: string }>('POST', '/bff/drafts/current/submission')
      sessionStorage.setItem('cw.ref', data.applicationRef)
      navigate('/submitted/' + data.applicationId)
    } catch (err) {
      const ae = err as ApiError
      setMessage(ae.code === 'ALREADY_IN_PROGRESS' ? t('check.inProgress') : ae.status === 503 || ae.status === 429
        ? t('error.busy', { s: ae.retryAfterSeconds ?? 30 }) : t('error.generic'))
    } finally {
      setSending(false)
    }
  }

  const show = (section: string, field: string, v: unknown) => {
    if (v === undefined || v === null || v === '') return null
    if (field === 'employmentType') return t('f.' + v)
    if (field === 'monthlyIncome' || field === 'monthlyObligation') return '₹' + Number(v).toLocaleString('en-IN')
    if (field === 'dateOfBirth') return new Date(String(v)).toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' })
    void section
    return String(v)
  }

  if (!draft) return <p role="status">…</p>
  return (
    <Page title={t('check.title')} back={() => navigate('/apply')}>
      <ErrorSummary errors={{}} message={message} />
      {SECTIONS.map((section) => (
        <section key={section}>
          <h2 className="heading-m">{t('section.' + section)}</h2>
          <dl className="summary-list">
            {FIELDS[section].map((field) => {
              const value = show(section, field, draft.answers[section]?.[field])
              return value === null ? null : (
                <div className="summary-row" key={field}>
                  <dt>{t('f.' + field)}</dt>
                  <dd>{value}</dd>
                  <dd className="summary-action">
                    <a href={'/apply/' + section} onClick={(e) => { e.preventDefault(); navigate('/apply/' + section) }}>
                      {t('change')}<span className="visually-hidden"> {t('f.' + field).toLowerCase()}</span>
                    </a>
                  </dd>
                </div>
              )
            })}
          </dl>
        </section>
      ))}
      <p>{t('check.declaration')}</p>
      <Button type="button" onClick={submit} disabled={sending}>{t('check.submit')}</Button>
    </Page>
  )
}
