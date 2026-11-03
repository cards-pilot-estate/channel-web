import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type Session } from './api'
import { Button } from './components/forms'
import { SessionContext } from './context'
import { LocaleContext, translate, type Locale } from './i18n'
import { Applications, Submitted } from './pages/Applications'
import { Check } from './pages/Check'
import { Accessibility, NotFound, SignedOut } from './pages/Other'
import { Privacy } from './pages/Privacy'
import { Section } from './pages/Section'
import { Code, SignIn, Start } from './pages/SignIn'
import { Tasks } from './pages/Tasks'
import { navigate, usePath } from './router'

const SIGNED_IN = /^\/(privacy-notice|apply|check-answers|submitted|applications)/

function Route({ path }: { path: string }) {
  const m = (re: RegExp) => path.match(re)
  if (path === '/' || path === '/start') return <Start />
  if (path === '/sign-in') return <SignIn />
  if (path === '/sign-in/code') return <Code />
  if (path === '/privacy-notice') return <Privacy />
  if (path === '/apply') return <Tasks />
  const section = m(/^\/apply\/(card|about-you|address|work)$/)
  if (section) return <Section key={section[1]} name={section[1]} />
  if (path === '/check-answers') return <Check />
  const submitted = m(/^\/submitted\/([0-9a-f-]{36})$/)
  if (submitted) return <Submitted id={submitted[1]} />
  const app = m(/^\/applications(?:\/([0-9a-f-]{36}))?$/)
  if (app) return <Applications id={app[1]} />
  if (path === '/signed-out') return <SignedOut />
  if (path === '/accessibility') return <Accessibility />
  return <NotFound />
}

/** HMRC pattern: warn at 13 minutes of inactivity, sign out at 15, say the answers are saved. */
function TimeoutWarning({ locale, onSignOut }: { locale: Locale; onSignOut: () => void }) {
  const [open, setOpen] = useState(false)
  const dialog = useRef<HTMLDialogElement>(null)
  const timers = useRef<number[]>([])
  const t = (k: string) => translate(locale, k)

  const arm = useCallback(() => {
    timers.current.forEach(window.clearTimeout)
    timers.current = [
      window.setTimeout(() => setOpen(true), 13 * 60 * 1000),
      window.setTimeout(onSignOut, 15 * 60 * 1000),
    ]
  }, [onSignOut])

  useEffect(() => {
    arm()
    const activity = () => { if (!open) arm() }
    window.addEventListener('keydown', activity)
    window.addEventListener('click', activity)
    return () => {
      timers.current.forEach(window.clearTimeout)
      window.removeEventListener('keydown', activity)
      window.removeEventListener('click', activity)
    }
  }, [arm, open])

  useEffect(() => {
    if (open) dialog.current?.showModal()
    else dialog.current?.close()
  }, [open])

  return (
    <dialog ref={dialog} aria-labelledby="timeout-title" aria-describedby="timeout-text" onCancel={(e) => e.preventDefault()}>
      <h2 id="timeout-title" className="heading-m">{t('timeout.title')}</h2>
      <p id="timeout-text">{t('timeout.text')}</p>
      <Button type="button" onClick={async () => { await api('POST', '/bff/session/extend'); setOpen(false); arm() }}>{t('timeout.stay')}</Button>
      <Button type="button" secondary onClick={onSignOut}>{t('signOut')}</Button>
    </dialog>
  )
}

export function App() {
  const path = usePath()
  const [session, setSession] = useState<Session | null>(null)
  const [locale, setLocaleState] = useState<Locale>(() => (localStorage.getItem('cw.locale') as Locale) ?? 'en-IN')
  const t = (k: string) => translate(locale, k)

  const refresh = useCallback(async () => {
    const { data } = await api<Session>('GET', '/bff/session')
    setSession(data)
  }, [])

  useEffect(() => { refresh() }, [refresh])
  useEffect(() => { document.documentElement.lang = locale }, [locale])
  useEffect(() => {
    if (session && !session.authenticated && SIGNED_IN.test(path)) navigate('/sign-in?next=' + encodeURIComponent(path), true)
  }, [session, path])

  const setLocale = (l: Locale) => { localStorage.setItem('cw.locale', l); setLocaleState(l) }
  const signOut = useCallback(async () => {
    try { await api('POST', '/bff/logout') } finally { await refresh(); navigate('/signed-out') }
  }, [refresh])

  return (
    <LocaleContext.Provider value={{ locale, setLocale }}>
      <SessionContext.Provider value={{ session, refresh }}>
        <a href="#main" className="skip-link">{t('skip')}</a>
        <header className="header">
          <div className="width-container header-inner">
            <a href="/" className="logo" onClick={(e) => { e.preventDefault(); navigate('/') }}>{t('brand')}</a>
            <nav aria-label="Account">
              <button type="button" className="link-button" lang={locale === 'en-IN' ? 'hi-IN' : 'en-IN'}
                onClick={() => setLocale(locale === 'en-IN' ? 'hi-IN' : 'en-IN')}>{t('lang.switch')}</button>
              {session?.authenticated && <button type="button" className="link-button" onClick={signOut}>{t('signOut')}</button>}
            </nav>
          </div>
        </header>
        <div className="width-container">
          <p className="phase-banner"><strong className="tag">DEMO</strong> {t('demo.banner')}</p>
          <main id="main" tabIndex={-1}>
            {session ? <Route path={path} /> : <p role="status">…</p>}
          </main>
        </div>
        <footer className="footer">
          <div className="width-container">
            <a href="/accessibility" onClick={(e) => { e.preventDefault(); navigate('/accessibility') }}>{t('a11y.title')}</a>
          </div>
        </footer>
        {session?.authenticated && <TimeoutWarning locale={locale} onSignOut={signOut} />}
      </SessionContext.Provider>
    </LocaleContext.Provider>
  )
}
