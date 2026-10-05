import { useMutation } from '@tanstack/react-query'
import { Info } from 'lucide-react'
import { useRef, type FormEvent } from 'react'
import { api, ApiError, type Session } from '../api'
import { Notice, Spinner, ThemeToggle, Wordmark } from '../components/ui'

const DEMO_ACCOUNTS = [
  { username: 'admin', reads: 'Every role' },
  { username: 'hr.manager', reads: 'HR, Employee' },
  { username: 'finance.analyst', reads: 'Finance, Employee' },
  { username: 'legal.counsel', reads: 'Legal, Employee' },
  { username: 'engineer', reads: 'Engineering, Employee' },
]

interface Props {
  notice: string | null
  onSignedIn: (session: Session) => void
}

export function LoginView({ notice, onSignedIn }: Props) {
  const usernameRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const login = useMutation({
    mutationFn: ({ username, password }: { username: string; password: string }) => api.login(username, password),
    onSuccess: onSignedIn,
    onError: () => passwordRef.current?.select(),
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    login.mutate({ username: String(data.get('username') ?? '').trim(), password: String(data.get('password') ?? '') })
  }

  function fillDemo(username: string) {
    if (usernameRef.current) usernameRef.current.value = username
    passwordRef.current?.focus()
  }

  const error =
    login.error instanceof ApiError && login.error.status === 401 ? 'The username or password is incorrect.' : login.error?.message

  return (
    <div className="min-h-dvh px-4 py-6 sm:py-10">
      <div className="mx-auto flex max-w-sm items-center justify-between">
        <Wordmark />
        <ThemeToggle />
      </div>

      <main id="main" className="mx-auto mt-12 max-w-sm sm:mt-20">
        {notice && (
          <p role="status" className="mb-4 flex gap-2.5 rounded-panel border border-rule-strong bg-sunken p-3 text-sm text-ink">
            <Info className="mt-0.5 size-4 shrink-0 text-ink-2" aria-hidden="true" />
            {notice}
          </p>
        )}

        <form onSubmit={submit} className="rounded-panel border border-rule bg-surface p-6">
          <h1 className="text-2xl font-bold tracking-[-0.01em]">Sign in</h1>
          <p className="mt-1 text-sm text-ink-2">You see only the documents your roles allow.</p>

          <div className="mt-6 space-y-4">
            <div>
              <label htmlFor="username" className="text-sm font-semibold">
                Username
              </label>
              <input
                ref={usernameRef}
                id="username"
                name="username"
                type="text"
                required
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                className="field mt-1.5"
              />
            </div>
            <div>
              <label htmlFor="password" className="text-sm font-semibold">
                Password
              </label>
              <input
                ref={passwordRef}
                id="password"
                name="password"
                type="password"
                required
                autoComplete="current-password"
                aria-describedby={error ? 'login-error' : undefined}
                className="field mt-1.5"
              />
            </div>
          </div>

          {error && (
            <div id="login-error" className="mt-4">
              <Notice title="Could not sign in">{error}</Notice>
            </div>
          )}

          <button type="submit" className="btn btn-primary mt-6 w-full" disabled={login.isPending}>
            {login.isPending && <Spinner />}
            {login.isPending ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <section aria-labelledby="demo-title" className="mt-6 rounded-panel border border-dashed border-rule-strong p-4">
          <h2 id="demo-title" className="text-sm font-semibold">
            Demo accounts
          </h2>
          <p className="mt-0.5 text-xs text-ink-2">Each uses the password <code className="font-mono">demo-password</code> unless <code className="font-mono">DEMO_PASSWORD</code> is set in <code className="font-mono">.env</code>. Pick one to fill in the username.</p>
          <ul className="mt-3 divide-y divide-rule">
            {DEMO_ACCOUNTS.map((account) => (
              <li key={account.username} className="flex items-center justify-between gap-3 py-1.5">
                <button
                  type="button"
                  onClick={() => fillDemo(account.username)}
                  className="cursor-pointer rounded-tag font-mono text-sm text-accent underline decoration-accent/40 underline-offset-3 hover:decoration-accent active:bg-accent-soft"
                >
                  {account.username}
                </button>
                <span className="text-xs text-ink-3">{account.reads}</span>
              </li>
            ))}
          </ul>
        </section>
      </main>
    </div>
  )
}
