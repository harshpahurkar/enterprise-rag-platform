import { useMutation } from '@tanstack/react-query'
import { CornerDownLeft, Info } from 'lucide-react'
import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { api, ApiError, type AskResponse, type Source } from '../api'
import { SourceCard } from '../components/SourceCard'
import { Notice, Spinner } from '../components/ui'

const msFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 })
const formatMs = (ms: number) => `${msFormat.format(ms)} ms`

// Matches [1] and [1, 2]. The capture group keeps markers in the split output.
const CITATION = /(\[\d+(?:\s*,\s*\d+)*\])/g

function citedNumbers(answer: string) {
  const cited = new Set<number>()
  for (const match of answer.matchAll(CITATION)) {
    for (const n of match[1].slice(1, -1).split(',')) cited.add(Number(n))
  }
  return cited
}

export function AskView() {
  const ask = useMutation({ mutationFn: api.ask })
  const [empty, setEmpty] = useState(false)
  const inputRef = useRef<HTMLTextAreaElement>(null)

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const question = String(new FormData(event.currentTarget).get('question') ?? '').trim()
    setEmpty(!question)
    if (!question) {
      inputRef.current?.focus()
      return
    }
    ask.mutate(question)
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      event.currentTarget.form?.requestSubmit()
    }
  }

  return (
    <section aria-labelledby="ask-title">
      <div className="max-w-3xl">
        <h1 id="ask-title" className="text-2xl font-bold tracking-[-0.01em] sm:text-[1.75rem]">
          Ask a question
        </h1>
        <p className="mt-1.5 text-ink-2">
          Answers use only the documents your roles can read. Each claim cites a numbered source you can check.
        </p>
      </div>

      <form onSubmit={submit} className="mt-6 max-w-3xl" noValidate>
        <label htmlFor="question" className="text-sm font-semibold">
          Question
        </label>
        <div className="mt-1.5 rounded-panel border border-rule-strong bg-surface has-[textarea:focus-visible]:border-accent has-[textarea:focus-visible]:outline-2 has-[textarea:focus-visible]:outline-offset-2 has-[textarea:focus-visible]:outline-accent">
          <textarea
            ref={inputRef}
            id="question"
            name="question"
            rows={3}
            maxLength={1000}
            required
            autoComplete="off"
            aria-invalid={empty || undefined}
            aria-describedby={empty ? 'question-error' : 'question-hint'}
            onKeyDown={onKeyDown}
            onInput={() => empty && setEmpty(false)}
            placeholder="How do we roll back a failed deploy?"
            className="block w-full resize-y rounded-t-panel bg-transparent px-3.5 pt-3 pb-2 font-serif text-[1.0625rem] leading-relaxed placeholder:text-ink-3 focus-visible:outline-none"
          />
          <div className="flex items-center justify-between gap-3 border-t border-rule px-3.5 py-2">
            <p id="question-hint" className="hidden text-xs text-ink-3 sm:block">
              <kbd className="font-mono">Enter</kbd> to ask, <kbd className="font-mono">Shift</kbd>+<kbd className="font-mono">Enter</kbd> for a new line
            </p>
            <button type="submit" className="btn btn-primary ml-auto" disabled={ask.isPending}>
              {ask.isPending ? <Spinner /> : <CornerDownLeft className="size-4" aria-hidden="true" />}
              {ask.isPending ? 'Asking…' : 'Ask'}
            </button>
          </div>
        </div>
        {empty && (
          <p id="question-error" className="mt-2 text-sm text-danger">
            Type a question first.
          </p>
        )}
      </form>

      <p role="status" className="sr-only">
        {ask.isPending && 'Retrieving passages and writing an answer.'}
        {ask.data && (ask.data.refused ? 'No answer found in your documents.' : `Answer ready with ${ask.data.sources.length} sources.`)}
      </p>

      <div className="mt-10">
        {ask.isIdle && (
          <p className="max-w-3xl border-l-2 border-rule pl-4 text-ink-2">
            Your answer appears here. Select a number like <CiteSample /> in the answer to jump to the passage it came from.
          </p>
        )}
        {ask.isPending && (
          <p className="flex items-center gap-2.5 text-ink-2">
            <Spinner />
            Retrieving passages and writing an answer…
          </p>
        )}
        {ask.isError && <AskError error={ask.error} />}
        {ask.data && <AnswerResult data={ask.data} />}
      </div>
    </section>
  )
}

function AskError({ error }: { error: Error }) {
  if (error instanceof ApiError && error.status === 422) {
    return (
      <div className="max-w-3xl">
        <Notice title="This question was blocked">{error.message}</Notice>
      </div>
    )
  }
  return (
    <div className="max-w-3xl">
      <Notice title="Could not get an answer">{error.message}</Notice>
    </div>
  )
}

function CiteSample() {
  return (
    <span className="inline-flex h-5 min-w-5 items-center justify-center rounded-tag border border-accent/40 bg-accent-soft px-1 align-[0.1em] font-mono text-[0.6875rem] font-semibold text-ink">
      1
    </span>
  )
}

function AnswerResult({ data }: { data: AskResponse }) {
  // tick re-triggers the scroll and flash when the same citation is chosen twice.
  const [active, setActive] = useState<{ n: number; tick: number } | null>(null)
  const cited = citedNumbers(data.answer)
  const cite = (n: number) => setActive((prev) => ({ n, tick: (prev?.tick ?? 0) + 1 }))

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,7fr)_minmax(0,5fr)] lg:items-start">
      <article
        aria-labelledby="answer-title"
        className={`rounded-panel border p-5 sm:p-6 lg:sticky lg:top-6 lg:max-h-[calc(100dvh-3rem)] lg:overflow-y-auto ${
          data.refused ? 'border-dashed border-rule-strong bg-sunken' : 'border-rule bg-surface'
        }`}
      >
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <h2 id="answer-title" className="font-semibold">
            {data.refused ? 'Not answered from your documents' : 'Answer'}
          </h2>
          <dl className="flex flex-wrap gap-1.5 font-mono text-xs">
            <Timing label="Retrieval" ms={data.retrievalMs} />
            <Timing label="Generation" ms={data.generationMs} />
          </dl>
        </div>
        {data.refused ? (
          <div className="mt-4 flex gap-3">
            <Info className="mt-1 size-5 shrink-0 text-ink-2" aria-hidden="true" />
            <div className="space-y-3">
              <p className="font-serif text-[1.0625rem] leading-relaxed whitespace-pre-wrap text-ink">{data.answer}</p>
              <p className="text-sm text-ink-2">
                Try wording closer to the document’s own terms. If the document exists but is not listed under Documents, ask an
                admin to share it with your role.
              </p>
            </div>
          </div>
        ) : (
          <AnswerText text={data.answer} sources={data.sources} activeN={active?.n} onCite={cite} />
        )}
      </article>

      <section aria-labelledby="sources-title">
        <h2 id="sources-title" className="flex items-baseline gap-2 font-semibold">
          {data.refused ? 'Closest passages' : 'Sources'}
          <span className="font-mono text-xs font-normal text-ink-3 tabular-nums">{data.sources.length}</span>
        </h2>
        {data.sources.length === 0 ? (
          <p className="mt-3 text-ink-2">No passages from your documents came close to this question.</p>
        ) : (
          <ol className="mt-3 space-y-3">
            {data.sources.map((source) => (
              <li key={source.chunkId}>
                <SourceCard
                  label={source.n}
                  chunk={source}
                  active={active?.n === source.n}
                  flash={active?.n === source.n ? active.tick : 0}
                  note={data.refused || cited.has(source.n) ? undefined : 'not cited in the answer'}
                />
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  )
}

function Timing({ label, ms }: { label: string; ms: number }) {
  return (
    <div className="flex gap-1.5 rounded-tag border border-rule px-1.5 py-0.5">
      <dt className="text-ink-3">{label}</dt>
      <dd className="text-ink tabular-nums">{formatMs(ms)}</dd>
    </div>
  )
}

interface AnswerTextProps {
  text: string
  sources: Source[]
  activeN?: number
  onCite: (n: number) => void
}

/** Plain text only: React escapes every string, and citation markers become buttons. */
function AnswerText({ text, sources, activeN, onCite }: AnswerTextProps) {
  const byN = new Map(sources.map((s) => [s.n, s]))

  return (
    <p className="mt-4 max-w-[68ch] font-serif text-[1.0625rem] leading-[1.75] break-words whitespace-pre-wrap">
      {text.split(CITATION).map((part, i) => {
        if (i % 2 === 0) return part
        const numbers = part.slice(1, -1).split(',').map(Number)
        if (!numbers.every((n) => byN.has(n))) return part
        return (
          <span key={i} className="whitespace-nowrap">
            {numbers.map((n) => (
              <CiteChip key={n} n={n} title={byN.get(n)!.title} active={activeN === n} onCite={onCite} />
            ))}
          </span>
        )
      })}
    </p>
  )
}

function CiteChip({ n, title, active, onCite }: { n: number; title: string; active: boolean; onCite: (n: number) => void }) {
  return (
    <button
      type="button"
      onClick={() => onCite(n)}
      aria-label={`Source ${n}: ${title}`}
      aria-pressed={active}
      className={`mx-px inline-flex h-6 min-w-6 cursor-pointer items-center justify-center rounded-tag border px-1 align-[0.1em] font-mono text-xs leading-none font-semibold tabular-nums transition-colors duration-100 active:translate-y-px ${
        active
          ? 'border-on-mark/40 bg-mark text-on-mark'
          : 'border-accent/40 bg-accent-soft text-ink hover:border-accent hover:bg-mark-soft'
      }`}
    >
      {n}
    </button>
  )
}
