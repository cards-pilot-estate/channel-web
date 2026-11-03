import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError, type Card, type Draft } from '../api'
import { Button, Checkbox, ErrorSummary, Page, Radios, TextField, errorText } from '../components/forms'
import { useT } from '../i18n'
import { navigate } from '../router'

type Values = Record<string, string | boolean>

/** One section per page, pre-filled from the draft, saved with If-Match (another device may have changed it). */
export function Section({ name }: { name: string }) {
  const t = useT()
  const [values, setValues] = useState<Values>({})
  const [version, setVersion] = useState<number>()
  const [cards, setCards] = useState<Card[]>([])
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState<string>()

  useEffect(() => {
    api<Draft>('GET', '/bff/drafts/current').then(({ data }) => {
      const saved = (data.answers[name] ?? {}) as Values
      if (name === 'about-you' && typeof saved.dateOfBirth === 'string') {
        const [y, m, d] = saved.dateOfBirth.split('-')
        Object.assign(saved, { dobDay: String(Number(d)), dobMonth: String(Number(m)), dobYear: y })
      }
      setValues(Object.fromEntries(Object.entries(saved).map(([k, v]) => [k, typeof v === 'boolean' ? v : String(v)])))
      setVersion(data.rowVersion)
    }).catch((e: ApiError) => navigate(e.status === 401 ? '/signed-out' : '/apply', true))
    if (name === 'card') api<{ cards: Card[] }>('GET', '/bff/catalogue').then(({ data }) => setCards(data.cards))
  }, [name])

  const set = (k: string) => (v: string | boolean) => setValues((old) => ({ ...old, [k]: v }))
  const s = (k: string) => (typeof values[k] === 'string' ? (values[k] as string) : '')

  async function submit(e: FormEvent) {
    e.preventDefault()
    setErrors({}); setMessage(undefined)
    const body: Values = { ...values }
    if (name === 'about-you') {
      const pad = (v: string) => v.trim().padStart(2, '0')
      body.dateOfBirth = s('dobYear') && s('dobMonth') && s('dobDay') ? `${s('dobYear').trim()}-${pad(s('dobMonth'))}-${pad(s('dobDay'))}` : ''
      delete body.dobDay; delete body.dobMonth; delete body.dobYear
    }
    try {
      await api('PUT', '/bff/drafts/current/sections/' + name, body, { 'If-Match': String(version) })
      navigate('/apply')
    } catch (err) {
      const ae = err as ApiError
      if (ae.status === 400 && ae.fieldErrors.length) {
        setErrors(Object.fromEntries(ae.fieldErrors.map((f) => [f.field === 'dateOfBirth' ? 'dobDay' : f.field, errorText(t, f)])))
      } else if (ae.status === 412) {
        setMessage(t('error.generic'))
        location.reload()
      } else if (ae.status === 401) {
        navigate('/signed-out')
      } else {
        setMessage(t('error.unavailable'))
      }
    }
  }

  // Render the form only once the saved answers are in, so a late load never overwrites what was typed.
  if (version === undefined || (name === 'card' && cards.length === 0)) return <p role="status">…</p>
  return (
    <Page title={t('section.' + name)} back={() => navigate('/apply')}>
      <ErrorSummary errors={errors} message={message} />
      <form onSubmit={submit} noValidate>
        {name === 'card' && (
          <>
            <Radios id="cardType" legend={t('section.card')} value={s('cardType')} onChange={set('cardType')} error={errors.cardType}
              options={cards.map((c) => ({ value: c.code, label: c.name, hint: c.benefit + '. ' + t('card.fees', { j: c.joiningFee.toLocaleString('en-IN'), a: c.annualFee.toLocaleString('en-IN') }) }))} />
            <p><a href="/documents/kfs-MITC-2026.1.html" target="_blank" rel="noopener">{t('card.kfs')} (opens in a new tab)</a></p>
            <Checkbox id="kfsAcknowledged" label={t('card.kfsAck')} checked={values.kfsAcknowledged === true}
              onChange={set('kfsAcknowledged')} error={errors.kfsAcknowledged} />
          </>
        )}
        {name === 'about-you' && (
          <>
            <TextField id="fullName" label={t('f.fullName')} hint={t('f.fullName.hint')} value={s('fullName')} onChange={set('fullName')}
              error={errors.fullName} autoComplete="name" />
            <TextField id="pan" label={t('f.pan')} hint={t('f.pan.hint')} value={s('pan')} onChange={(v) => set('pan')(v.toUpperCase())}
              error={errors.pan} width="medium" />
            <fieldset className={'field' + (errors.dobDay ? ' field-error' : '')} role="group" aria-describedby="dob-hint">
              <legend>{t('f.dateOfBirth')}</legend>
              <div className="hint" id="dob-hint">{t('f.dateOfBirth.hint')}</div>
              {errors.dobDay && <p className="error-message">{errors.dobDay}</p>}
              <div className="date-input">
                <TextField id="dobDay" label={t('f.day')} value={s('dobDay')} onChange={set('dobDay')} inputMode="numeric" autoComplete="bday-day" width="short" />
                <TextField id="dobMonth" label={t('f.month')} value={s('dobMonth')} onChange={set('dobMonth')} inputMode="numeric" autoComplete="bday-month" width="short" />
                <TextField id="dobYear" label={t('f.year')} value={s('dobYear')} onChange={set('dobYear')} inputMode="numeric" autoComplete="bday-year" width="short" />
              </div>
            </fieldset>
            <TextField id="email" label={t('f.email')} value={s('email')} onChange={set('email')} error={errors.email} type="email"
              inputMode="email" autoComplete="email" />
          </>
        )}
        {name === 'address' && (
          <>
            <TextField id="addressLine1" label={t('f.addressLine1')} value={s('addressLine1')} onChange={set('addressLine1')} error={errors.addressLine1} autoComplete="address-line1" />
            <TextField id="addressLine2" label={t('f.addressLine2')} value={s('addressLine2')} onChange={set('addressLine2')} error={errors.addressLine2} autoComplete="address-line2" />
            <TextField id="city" label={t('f.city')} value={s('city')} onChange={set('city')} error={errors.city} autoComplete="address-level2" width="medium" />
            <TextField id="state" label={t('f.state')} value={s('state')} onChange={set('state')} error={errors.state} autoComplete="address-level1" width="medium" />
            <TextField id="pincode" label={t('f.pincode')} value={s('pincode')} onChange={set('pincode')} error={errors.pincode} inputMode="numeric" autoComplete="postal-code" width="short" />
          </>
        )}
        {name === 'work' && (
          <>
            <Radios id="employmentType" legend={t('f.employmentType')} value={s('employmentType')} onChange={set('employmentType')} error={errors.employmentType}
              options={['SALARIED', 'SELF_EMPLOYED', 'BUSINESS_OWNER'].map((v) => ({ value: v, label: t('f.' + v) }))} />
            <TextField id="employerName" label={t('f.employerName')} value={s('employerName')} onChange={set('employerName')} error={errors.employerName} autoComplete="organization" />
            <TextField id="monthlyIncome" label={t('f.monthlyIncome')} value={s('monthlyIncome')} onChange={set('monthlyIncome')} error={errors.monthlyIncome} inputMode="numeric" width="medium" />
            <TextField id="monthlyObligation" label={t('f.monthlyObligation')} hint={t('f.monthlyObligation.hint')} value={s('monthlyObligation')} onChange={set('monthlyObligation')} error={errors.monthlyObligation} inputMode="numeric" width="medium" />
          </>
        )}
        <Button>{t('saveContinue')}</Button>
      </form>
    </Page>
  )
}
