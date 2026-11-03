import { Page } from '../components/forms'
import { useT } from '../i18n'
import { navigate } from '../router'

export function SignedOut() {
  const t = useT()
  return (
    <Page title={t('signedOut.title')}>
      <p>{t('signedOut.text')}</p>
      <a href="/sign-in" className="button" role="button" onClick={(e) => { e.preventDefault(); navigate('/sign-in?next=/apply') }}>
        {t('signedOut.again')}
      </a>
    </Page>
  )
}

export function Accessibility() {
  const t = useT()
  return <Page title={t('a11y.title')}><p>{t('a11y.text')}</p></Page>
}

export function NotFound() {
  const t = useT()
  return <Page title={t('notFound.title')}><p><a href="/">{t('start.title')}</a></p></Page>
}
