import { useQueryClient } from '@tanstack/react-query'
import { FileText, LogOut, MessageSquareText, Search } from 'lucide-react'
import { Component, useEffect, useState, type ReactNode } from 'react'
import { SESSION_EXPIRED_EVENT, clearSession, loadSession, saveSession } from './api'
import { RoleTag, ThemeToggle, Wordmark } from './components/ui'
import { AskView } from './views/AskView'
import { DocumentsView } from './views/DocumentsView'
import { LoginView } from './views/LoginView'
import { SearchView } from './views/SearchView'

const VIEWS = [
  { id: 'ask', label: 'Ask', icon: MessageSquareText },
  { id: 'search', label: 'Search', icon: Search },
  { id: 'documents', label: 'Documents', icon: FileText },
] as const
type View = (typeof VIEWS)[number]['id']

// The tab lives in the URL hash so it survives reloads and can be linked.
function viewFromHash(): View {
  const hash = location.hash.slice(1)
  return VIEWS.find((v) => v.id === hash)?.id ?? 'ask'
}

export default function App() {
  const queryClient = useQueryClient()
  const [session, setSession] = useState(loadSession)
  const [notice, setNotice] = useState<string | null>(null)
  const [view, setView] = useState(viewFromHash)

  useEffect(() => {
    const onHash = () => setView(viewFromHash())
    const onExpired = () => {
      queryClient.clear()
      setSession(null)
      setNotice('Your session ended. Sign in again to continue.')
    }
    window.addEventListener('hashchange', onHash)
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired)
    return () => {
      window.removeEventListener('hashchange', onHash)
      window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired)
    }
  }, [queryClient])

  const label = VIEWS.find((v) => v.id === view)!.label
  useEffect(() => {
    document.title = session ? `${label} · Enterprise RAG` : 'Sign in · Enterprise RAG'
  }, [session, label])

  if (!session) {
    return (
      <LoginView
        notice={notice}
        onSignedIn={(next) => {
          saveSession(next)
          setNotice(null)
          setSession(next)
        }}
      />
    )
  }

  function signOut() {
    clearSession()
    queryClient.clear()
    setSession(null)
  }

  return (
    <>
      <a
        href="#main"
        onClick={(event) => {
          // Focus without touching the hash, which holds the current tab.
          event.preventDefault()
          document.getElementById('main')?.focus()
        }}
        className="sr-only z-10 rounded-panel bg-accent px-3 py-2 font-semibold text-on-accent focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        Skip to content
      </a>

      <header className="border-b border-rule bg-surface">
        <div className="mx-auto max-w-6xl px-4 sm:px-6">
          {/* Small screens: wordmark and buttons on one row, the signed-in user on the next. */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2 pt-3.5">
            <Wordmark />
            <div className="order-last flex w-full min-w-0 flex-wrap items-center gap-x-2.5 gap-y-1 sm:order-none sm:ml-auto sm:w-auto sm:justify-end">
              <span className="truncate text-sm font-semibold">
                <span className="sr-only">Signed in as </span>
                {session.username}
              </span>
              <span className="flex flex-wrap gap-1">
                {session.roles.map((role) => (
                  <RoleTag key={role} role={role} held />
                ))}
              </span>
            </div>
            <div className="ml-auto flex items-center gap-2 sm:ml-0">
              <ThemeToggle />
              <button type="button" onClick={signOut} className="btn btn-quiet btn-sm" aria-label="Sign out">
                <LogOut className="size-4" aria-hidden="true" />
                <span className="hidden sm:inline">Sign out</span>
              </button>
            </div>
          </div>

          <nav aria-label="Sections" className="-mb-px mt-2 flex gap-1 overflow-x-auto">
            {VIEWS.map(({ id, label, icon: Icon }) => (
              <a
                key={id}
                href={`#${id}`}
                aria-current={view === id ? 'page' : undefined}
                className="flex shrink-0 items-center gap-2 border-b-2 border-transparent px-3 py-2.5 text-sm font-semibold text-ink-2 transition-colors duration-100 hover:text-ink active:bg-sunken aria-[current=page]:border-accent aria-[current=page]:text-ink"
              >
                <Icon className="size-4" aria-hidden="true" />
                {label}
              </a>
            ))}
          </nav>
        </div>
      </header>

      {/* deliberate-ignore outline-none: main is only a skip-link focus target, not a control; a ring around the page adds nothing. */}
      <main id="main" tabIndex={-1} className="mx-auto max-w-6xl px-4 py-8 focus:outline-none sm:px-6 sm:py-12">
        <ErrorBoundary>
          {/* Views stay mounted so an answer or a result list survives switching tabs. */}
          <div hidden={view !== 'ask'}>
            <AskView />
          </div>
          <div hidden={view !== 'search'}>
            <SearchView />
          </div>
          <div hidden={view !== 'documents'}>
            <DocumentsView session={session} />
          </div>
        </ErrorBoundary>
      </main>
    </>
  )
}

class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <div role="alert" className="max-w-xl rounded-panel border border-danger/40 bg-danger-soft p-5">
        <p className="font-semibold">This page hit an error and stopped.</p>
        <p className="mt-1 text-sm break-words text-ink-2">{this.state.error.message}</p>
        <button type="button" className="btn btn-quiet btn-sm mt-4" onClick={() => location.reload()}>
          Reload the page
        </button>
      </div>
    )
  }
}
