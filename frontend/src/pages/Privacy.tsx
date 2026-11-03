import { useState, type FormEvent } from 'react'
import { api, ApiError } from '../api'
import { Button, Checkbox, ErrorSummary, Page } from '../components/forms'
import { useSession } from '../context'
import { LocaleContext, useT } from '../i18n'
import { navigate } from '../router'
import { useContext } from 'react'

/** S05/S06: the standalone notice and purpose-wise consent, before any personal data (R3). Nothing pre-ticked. */
export function Privacy() {
  const t = useT()
  const { locale } = useContext(LocaleContext)
  const { session } = useSession()
  const [processing, setProcessing] = useState(false)
  const [bureau, setBureau] = useState(false)
  const [marketing, setMarketing] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState<string>()

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!processing || !bureau) {
      setErrors(processing ? { bureau: t('privacy.required') } : { processing: t('privacy.required') })
      return
    }
    try {
      await api('POST', '/bff/drafts', { noticeVersion: session?.noticeVersion, locale,
        purposes: { PROCESSING: processing, BUREAU_CHECK: bureau, MARKETING: marketing } })
      navigate('/apply')
    } catch (err) {
      setMessage((err as ApiError).status === 401 ? undefined : t('error.generic'))
      if ((err as ApiError).status === 401) navigate('/signed-out')
    }
  }

  return (
    <Page title={t('privacy.title')}>
      <ErrorSummary errors={errors} message={message} />
      <p className="lead">{t('privacy.lead')}</p>
      {t('privacy.legalNote') && <p className="inset" lang="hi-IN">{t('privacy.legalNote')}</p>}
      <section className="notice-box" aria-labelledby="notice-title" lang="en-IN">
        <h2 id="notice-title" className="heading-s">Privacy notice ({session?.noticeVersion})</h2>
        <p>Ondaro Bank (the data fiduciary) uses the details you give in this application to decide whether to offer you a
          credit card, to check your identity and credit history, and to meet legal obligations. A credit bureau sees your
          PAN and personal details for the credit check. We keep an unfinished application for 30 days. You can withdraw
          consent, ask for your data to be erased (where the law allows), or complain to our grievance officer or the Data
          Protection Board of India.</p>
      </section>
      <form onSubmit={submit} noValidate>
        <fieldset className="field">
          <legend className="heading-s">{t('privacy.choose')}</legend>
          <Checkbox id="processing" label={t('privacy.processing')} checked={processing} onChange={setProcessing} error={errors.processing} />
          <Checkbox id="bureau" label={t('privacy.bureau')} checked={bureau} onChange={setBureau} error={errors.bureau} />
          <Checkbox id="marketing" label={t('privacy.marketing')} checked={marketing} onChange={setMarketing} />
        </fieldset>
        <p className="hint">{t('privacy.retention')}</p>
        <Button>{t('continue')}</Button>
      </form>
    </Page>
  )
}
