import { useEffect, useState } from 'react'
import { api, ApiError, type Draft } from '../api'
import { Page } from '../components/forms'
import { useT } from '../i18n'
import { navigate } from '../router'

export const SECTIONS = ['card', 'about-you', 'address', 'work'] as const

/** S07: the task list ("Complete multiple tasks"). */
export function Tasks() {
  const t = useT()
  const [draft, setDraft] = useState<Draft | null>(null)

  useEffect(() => {
    api<Draft>('GET', '/bff/drafts/current').then(({ data }) => setDraft(data)).catch((e: ApiError) => {
      if (e.status === 404) navigate('/privacy-notice', true)
      if (e.status === 401) navigate('/signed-out', true)
    })
  }, [])

  if (!draft) return <p role="status">…</p>
  return (
    <Page title={t('tasks.title')}>
      <p>{t('tasks.lead')}</p>
      <ol className="task-list">
        {SECTIONS.map((s) => (
          <li key={s} className="task">
            <a href={'/apply/' + s} onClick={(e) => { e.preventDefault(); navigate('/apply/' + s) }} aria-describedby={s + '-status'}>
              {t('section.' + s)}
            </a>
            <strong id={s + '-status'} className={'tag' + (draft.sectionsDone.includes(s) ? '' : ' tag-grey')}>
              {draft.sectionsDone.includes(s) ? t('tasks.completed') : t('tasks.notStarted')}
            </strong>
          </li>
        ))}
      </ol>
      {draft.complete && (
        <a href="/check-answers" className="button" role="button" onClick={(e) => { e.preventDefault(); navigate('/check-answers') }}>
          {t('tasks.check')}
        </a>
      )}
      <p className="hint">{t('tasks.expires', { d: new Date(draft.expiresAt).toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' }) })}</p>
    </Page>
  )
}
