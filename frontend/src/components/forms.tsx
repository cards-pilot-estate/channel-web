// Form building blocks following published GOV.UK interaction patterns (error summary, hint, inline error),
// under the fictional brand. Every input has a visible label; errors are linked from the summary.
import { useEffect, useRef, type ReactNode } from 'react'
import type { FieldError } from '../api'
import { useT } from '../i18n'

export type Errors = Record<string, string>

export function errorText(t: ReturnType<typeof useT>, e: FieldError): string {
  const name = t('f.' + e.field).toLowerCase()
  if (e.code === 'tooYoung') return t('err.tooYoung')
  if (e.code === 'range') return t('err.range')
  if (e.field === 'kfsAcknowledged') return t('err.ack')
  if (e.field === 'employmentType' || e.field === 'cardType') return t('err.choose', { f: name })
  return t(e.code === 'format' ? 'err.format' : 'err.required', { f: name })
}

export function ErrorSummary({ errors, message }: { errors: Errors; message?: string }) {
  const t = useT()
  const ref = useRef<HTMLDivElement>(null)
  const entries = Object.entries(errors)
  useEffect(() => {
    if (entries.length > 0 || message) ref.current?.focus()
  }, [entries.length, message])
  if (entries.length === 0 && !message) return null
  return (
    <div className="error-summary" role="alert" tabIndex={-1} ref={ref} aria-labelledby="error-summary-title">
      <h2 id="error-summary-title">{t('error.title')}</h2>
      {message && <p>{message}</p>}
      {entries.length > 0 && (
        <ul>
          {entries.map(([field, text]) => (
            <li key={field}>
              <a href={'#' + field} onClick={(ev) => { ev.preventDefault(); document.getElementById(field)?.focus() }}>{text}</a>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

type InputProps = {
  id: string
  label: string
  hint?: string
  error?: string
  value: string
  onChange: (v: string) => void
  autoComplete?: string
  inputMode?: 'text' | 'numeric' | 'tel' | 'email' | 'decimal'
  type?: string
  width?: 'short' | 'medium' | 'full'
  spellCheck?: boolean
}

export function TextField(p: InputProps) {
  const describedBy = [p.hint ? p.id + '-hint' : '', p.error ? p.id + '-error' : ''].filter(Boolean).join(' ') || undefined
  return (
    <div className={'field' + (p.error ? ' field-error' : '')}>
      <label htmlFor={p.id}>{p.label}</label>
      {p.hint && <div id={p.id + '-hint'} className="hint">{p.hint}</div>}
      {p.error && <p id={p.id + '-error'} className="error-message"><span className="visually-hidden">Error: </span>{p.error}</p>}
      <input id={p.id} name={p.id} type={p.type ?? 'text'} value={p.value} onChange={(e) => p.onChange(e.target.value)}
        autoComplete={p.autoComplete} inputMode={p.inputMode} spellCheck={p.spellCheck ?? false}
        aria-describedby={describedBy} aria-invalid={p.error ? true : undefined} className={'input-' + (p.width ?? 'full')} />
    </div>
  )
}

export function Radios(p: { id: string; legend: string; options: { value: string; label: string; hint?: string }[];
  value: string; onChange: (v: string) => void; error?: string }) {
  return (
    <fieldset className={'field' + (p.error ? ' field-error' : '')} aria-describedby={p.error ? p.id + '-error' : undefined}>
      <legend>{p.legend}</legend>
      {p.error && <p id={p.id + '-error'} className="error-message"><span className="visually-hidden">Error: </span>{p.error}</p>}
      {p.options.map((o, i) => (
        <div className="radio" key={o.value}>
          <input type="radio" id={i === 0 ? p.id : p.id + '-' + o.value} name={p.id} value={o.value}
            checked={p.value === o.value} onChange={() => p.onChange(o.value)}
            aria-describedby={o.hint ? p.id + '-' + o.value + '-hint' : undefined} />
          <label htmlFor={i === 0 ? p.id : p.id + '-' + o.value}>{o.label}</label>
          {o.hint && <div className="hint" id={p.id + '-' + o.value + '-hint'}>{o.hint}</div>}
        </div>
      ))}
    </fieldset>
  )
}

export function Checkbox(p: { id: string; label: ReactNode; checked: boolean; onChange: (v: boolean) => void; error?: string }) {
  return (
    <div className={'field' + (p.error ? ' field-error' : '')}>
      {p.error && <p id={p.id + '-error'} className="error-message"><span className="visually-hidden">Error: </span>{p.error}</p>}
      <div className="checkbox">
        <input type="checkbox" id={p.id} checked={p.checked} onChange={(e) => p.onChange(e.target.checked)}
          aria-describedby={p.error ? p.id + '-error' : undefined} />
        <label htmlFor={p.id}>{p.label}</label>
      </div>
    </div>
  )
}

export function Button({ children, onClick, secondary, disabled, type }: { children: ReactNode; onClick?: () => void;
  secondary?: boolean; disabled?: boolean; type?: 'button' | 'submit' }) {
  return <button type={type ?? 'submit'} className={secondary ? 'button secondary' : 'button'} onClick={onClick} disabled={disabled}>{children}</button>
}

export function Page({ title, children, back }: { title: string; children: ReactNode; back?: () => void }) {
  const t = useT()
  const ref = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    document.title = title + ' · ' + t('brand')
    ref.current?.focus()
  }, [title])
  return (
    <>
      {back && <a href="#" className="back-link" onClick={(e) => { e.preventDefault(); back() }}>{t('back')}</a>}
      <h1 tabIndex={-1} ref={ref}>{title}</h1>
      {children}
    </>
  )
}
