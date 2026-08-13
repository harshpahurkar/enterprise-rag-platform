import { useMutation } from '@tanstack/react-query'
import { Search } from 'lucide-react'
import type { FormEvent } from 'react'
import { api } from '../api'
import { SourceCard } from '../components/SourceCard'
import { Notice, Spinner } from '../components/ui'

export function SearchView() {
  const search = useMutation({ mutationFn: api.search })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const query = String(new FormData(event.currentTarget).get('query') ?? '').trim()
    if (query) search.mutate(query)
  }

  const results = search.data?.results

  return (
    <section aria-labelledby="search-title">
      <div className="max-w-3xl">
        <h1 id="search-title" className="text-2xl font-bold tracking-[-0.01em] sm:text-[1.75rem]">
          Search passages
        </h1>
        <p className="mt-1.5 text-ink-2">
          Ranks passages by meaning, with no generated answer. Only documents your roles can read are searched.
        </p>
      </div>

      <form role="search" onSubmit={submit} className="mt-6 flex max-w-3xl gap-2">
        <label htmlFor="query" className="sr-only">
          Search query
        </label>
        <input
          id="query"
          name="query"
          type="search"
          required
          maxLength={1000}
          autoComplete="off"
          enterKeyHint="search"
          placeholder="Search policies, runbooks, contracts…"
          className="field min-w-0 flex-1"
        />
        <button type="submit" className="btn btn-primary" disabled={search.isPending}>
          {search.isPending ? <Spinner /> : <Search className="size-4" aria-hidden="true" />}
          <span className={search.isPending ? undefined : 'max-[400px]:sr-only'}>{search.isPending ? 'Searching…' : 'Search'}</span>
        </button>
      </form>

      <p role="status" className="sr-only">
        {search.data && `${search.data.results.length} passages found in ${Math.round(search.data.retrievalMs)} milliseconds.`}
      </p>

      <div className="mt-10" aria-busy={search.isPending}>
        {search.isIdle && (
          <p className="max-w-3xl border-l-2 border-rule pl-4 text-ink-2">
            Results list the closest passages first, with the document, the part it came from, and a similarity score.
          </p>
        )}
        {search.isPending && (
          <p className="flex items-center gap-2.5 text-ink-2">
            <Spinner />
            Searching…
          </p>
        )}
        {search.isError && (
          <div className="max-w-3xl">
            <Notice title="Search failed">{search.error.message}</Notice>
          </div>
        )}
        {search.data && results && (
          <>
            <div className="flex flex-wrap items-end justify-between gap-4 border-b border-rule pb-4">
              <h2 data-testid="search-summary" className="min-w-0 text-lg font-semibold break-words">
                {results.length} {results.length === 1 ? 'passage' : 'passages'} for “{search.variables}”
              </h2>
              <p
                data-testid="retrieval-latency"
                className="inline-flex items-baseline gap-1.5 rounded-panel border border-accent bg-accent-soft px-3 py-1.5 text-ink"
              >
                <span className="text-xs text-ink-2">Retrieved in</span>{' '}
                <span className="font-mono text-2xl leading-none font-semibold tabular-nums">{Math.round(search.data.retrievalMs)}</span>{' '}
                <span className="font-mono text-xs">ms</span>
              </p>
            </div>
            {results.length === 0 ? (
              <p className="mt-6 max-w-3xl text-ink-2">
                No passages matched. Try other words, or ask an admin whether the document you need is shared with your role.
              </p>
            ) : (
              <ol aria-label="Search results" className="mt-6 max-w-4xl space-y-3">
                {results.map((chunk, i) => (
                  <li key={chunk.chunkId}>
                    <SourceCard label={i + 1} chunk={chunk} />
                  </li>
                ))}
              </ol>
            )}
          </>
        )}
      </div>
    </section>
  )
}
