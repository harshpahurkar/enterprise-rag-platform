import { useEffect, useId, useRef } from 'react'
import type { Chunk } from '../api'

interface Props {
  /** Citation number in Ask, rank in Search. Shown as the margin numeral. */
  label: number
  chunk: Chunk
  /** Persistent highlight: this card is the one the reader last jumped to. */
  active?: boolean
  /** Bump to scroll to the card, focus it, and flash the highlighter. 0 does nothing. */
  flash?: number
  note?: string
}

const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches

export function SourceCard({ label, chunk, active = false, flash = 0, note }: Props) {
  const cardRef = useRef<HTMLElement>(null)
  const titleId = useId()
  const markRef = useRef<HTMLSpanElement>(null)

  useEffect(() => {
    const card = cardRef.current
    if (!flash || !card) return
    const still = reducedMotion()
    card.scrollIntoView({ behavior: still ? 'auto' : 'smooth', block: 'nearest' })
    card.focus({ preventScroll: true })
    if (!still) {
      markRef.current?.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 1400, easing: 'cubic-bezier(0.2, 0, 0, 1)' })
    }
  }, [flash])

  const score = Math.min(Math.max(chunk.score, 0), 1)

  return (
    <article
      ref={cardRef}
      tabIndex={-1}
      aria-labelledby={titleId}
      className={`relative isolate grid scroll-mt-6 grid-cols-[2.75rem_minmax(0,1fr)] rounded-panel border sm:grid-cols-[3.25rem_minmax(0,1fr)] ${
        active ? 'border-on-mark/30 bg-mark-soft' : 'border-rule bg-surface'
      }`}
    >
      <span ref={markRef} aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 rounded-panel bg-mark opacity-0" />
      <span
        aria-hidden="true"
        className={`flex justify-center rounded-l-panel border-r pt-4 font-mono text-xl leading-none font-semibold tabular-nums sm:text-2xl ${
          active ? 'border-on-mark/20 bg-mark text-on-mark' : 'border-rule text-ink-3'
        }`}
      >
        {label}
      </span>
      <div className="min-w-0 p-4">
        <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1.5">
          <div className="min-w-0">
            <h3 id={titleId} className="font-semibold break-words text-ink">
              <span className="sr-only">{label}. </span>
              {chunk.title}
            </h3>
            <p className="mt-0.5 font-mono text-xs text-ink-2">
              Part {chunk.chunkIndex + 1}
              {note && <span className="font-sans text-ink-3"> · {note}</span>}
            </p>
          </div>
          <div className="flex items-center gap-2" title="Similarity score, 0 to 1">
            <span className="sr-only">Similarity score</span>
            <span aria-hidden="true" className="h-1.5 w-16 overflow-hidden rounded-full bg-sunken">
              <span className="block h-full rounded-full bg-accent" style={{ width: `${score * 100}%` }} />
            </span>
            <span className="font-mono text-xs text-ink tabular-nums">{chunk.score.toFixed(3)}</span>
          </div>
        </div>
        <p className="mt-3 font-serif text-[0.9375rem] leading-relaxed break-words whitespace-pre-wrap text-ink">{chunk.content}</p>
      </div>
    </article>
  )
}
