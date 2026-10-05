import { CircleAlert, LoaderCircle, Moon, Sun } from 'lucide-react'
import { useState, type ReactNode } from 'react'

export function Spinner() {
  return <LoaderCircle className="size-4 shrink-0 animate-spin" aria-hidden="true" />
}

export function RoleTag({ role, held }: { role: string; held?: boolean }) {
  return (
    <span className="tag" data-held={held || undefined} translate="no">
      {role}
    </span>
  )
}

export function Wordmark() {
  return (
    <span className="inline-flex items-center gap-2.5 text-ink">
      <svg viewBox="0 0 32 32" className="size-7 shrink-0" aria-hidden="true">
        <rect width="32" height="32" rx="6" className="fill-accent" />
        <path d="M11 8H8v16h3M21 8h3v16h-3" fill="none" strokeWidth="2.5" className="stroke-on-accent" />
        <rect x="13" y="13" width="6" height="6" className="fill-mark" />
      </svg>
      <span className="text-[1.0625rem] leading-none font-bold tracking-[-0.01em]">
        Enterprise RAG <span className="hidden font-normal text-ink-2 sm:inline">Platform</span>
      </span>
    </span>
  )
}

/** Error or block message. role="alert" so it is announced when it appears. */
export function Notice({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div role="alert" className="flex gap-3 rounded-panel border border-danger/40 bg-danger-soft p-4 text-ink">
      <CircleAlert className="mt-0.5 size-5 shrink-0 text-danger" aria-hidden="true" />
      <div className="min-w-0 space-y-1">
        <p className="font-semibold">{title}</p>
        {children && <div className="break-words text-ink-2">{children}</div>}
        {action && <div className="pt-2">{action}</div>}
      </div>
    </div>
  )
}

const THEME_KEY = 'erp.theme'

// Runs once when this module loads, before the first render, so a saved theme never flashes.
try {
  const stored = localStorage.getItem(THEME_KEY)
  if (stored === 'light' || stored === 'dark') document.documentElement.dataset.theme = stored
} catch {
  // Storage blocked: follow the OS setting.
}

function currentTheme(): 'light' | 'dark' {
  const set = document.documentElement.dataset.theme
  if (set === 'light' || set === 'dark') return set
  return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function ThemeToggle() {
  const [theme, setTheme] = useState(currentTheme)
  const next = theme === 'dark' ? 'light' : 'dark'

  function toggle() {
    document.documentElement.dataset.theme = next
    try {
      localStorage.setItem(THEME_KEY, next)
    } catch {
      // Not persisted; the choice still applies for this page.
    }
    setTheme(next)
  }

  return (
    <button type="button" onClick={toggle} className="btn btn-quiet btn-sm px-2" aria-label={`Use ${next} theme`}>
      {theme === 'dark' ? <Sun className="size-4" aria-hidden="true" /> : <Moon className="size-4" aria-hidden="true" />}
    </button>
  )
}
