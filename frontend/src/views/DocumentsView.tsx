import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Trash2, Upload } from 'lucide-react'
import { Fragment, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { ROLES, api, type DocumentInfo, type Session } from '../api'
import { Notice, RoleTag, Spinner } from '../components/ui'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })
const DOCUMENTS = ['documents']

export function DocumentsView({ session }: { session: Session }) {
  const isAdmin = session.roles.includes('ADMIN')
  const documents = useQuery({ queryKey: DOCUMENTS, queryFn: api.documents })
  const [status, setStatus] = useState('')
  const [confirmingId, setConfirmingId] = useState<number | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)

  function onDeleted(doc: DocumentInfo) {
    setConfirmingId(null)
    setStatus(`Deleted “${doc.title}”.`)
    // The row and its button are gone; park focus on the list heading.
    headingRef.current?.focus()
  }

  const docs = documents.data

  return (
    <section aria-labelledby="documents-title">
      <div className="max-w-3xl">
        <h1 id="documents-title" className="text-2xl font-bold tracking-[-0.01em] sm:text-[1.75rem]">
          Documents
        </h1>
        <p className="mt-1.5 text-ink-2">
          {isAdmin
            ? 'Upload documents, choose which roles can read them, and remove ones that are out of date.'
            : 'The documents your roles can read. Ask and Search draw only on these.'}
        </p>
      </div>

      <p role="status" className="sr-only">
        {status}
      </p>

      {isAdmin && <UploadForm onUploaded={(doc) => setStatus(`Uploaded “${doc.title}” as ${doc.chunkCount} chunks.`)} />}

      <div className="mt-10 flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
        <h2 ref={headingRef} tabIndex={-1} className="flex items-baseline gap-2 font-semibold">
          Readable by you
          {docs && <span className="font-mono text-xs font-normal text-ink-3 tabular-nums">{docs.length}</span>}
        </h2>
        <p className="flex items-center gap-1.5 text-xs text-ink-2">
          <span aria-hidden="true" className="tag" data-held>
            ROLE
          </span>
          Highlighted roles are ones you hold
        </p>
      </div>

      <div className="mt-3">
        {documents.isPending && (
          <p className="flex items-center gap-2.5 py-4 text-ink-2">
            <Spinner />
            Loading documents…
          </p>
        )}
        {documents.isError && (
          <Notice
            title="Could not load documents"
            action={
              <button type="button" className="btn btn-quiet btn-sm" onClick={() => documents.refetch()} disabled={documents.isFetching}>
                {documents.isFetching ? 'Retrying…' : 'Try again'}
              </button>
            }
          >
            {documents.error.message}
          </Notice>
        )}
        {docs && docs.length === 0 && (
          <p className="rounded-panel border border-dashed border-rule-strong px-5 py-8 text-ink-2">
            {isAdmin
              ? 'No documents yet. Upload one above and choose which roles can read it.'
              : 'No documents are shared with your roles yet. Ask an admin to share the ones you need.'}
          </p>
        )}
        {docs && docs.length > 0 && (
          <div className="overflow-x-auto rounded-panel border border-rule bg-surface">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Documents you can read</caption>
              <thead className="bg-sunken text-xs text-ink-2">
                <tr>
                  <th scope="col" className="px-4 py-2.5 font-semibold">Document</th>
                  <th scope="col" className="hidden px-4 py-2.5 font-semibold sm:table-cell">Readable by</th>
                  <th scope="col" className="px-4 py-2.5 text-right font-semibold">Chunks</th>
                  <th scope="col" className="hidden px-4 py-2.5 font-semibold md:table-cell">Added</th>
                  {isAdmin && (
                    <th scope="col" className="px-4 py-2.5">
                      <span className="sr-only">Actions</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-rule">
                {docs.map((doc) => {
                  const roles = (
                    <span className="flex flex-wrap gap-1">
                      {doc.allowedRoles.map((role) => (
                        <RoleTag key={role} role={role} held={session.roles.includes(role)} />
                      ))}
                    </span>
                  )
                  const confirming = confirmingId === doc.id
                  return (
                    <Fragment key={doc.id}>
                      <tr className="align-top">
                        <th scope="row" className="max-w-[22rem] px-4 py-3 font-normal">
                          <span className="block font-semibold break-words text-ink">{doc.title}</span>
                          <span className="mt-0.5 block font-mono text-xs break-all text-ink-3">{doc.filename}</span>
                          {/* Below 640px the roles sit under the title so the table fits without scrolling. */}
                          <span className="mt-2 block sm:hidden">{roles}</span>
                        </th>
                        <td className="hidden px-4 py-3 sm:table-cell">{roles}</td>
                        <td className="px-4 py-3 text-right font-mono tabular-nums">{doc.chunkCount}</td>
                        <td className="hidden px-4 py-3 text-ink-2 md:table-cell">
                          <span className="block whitespace-nowrap">{dateFormat.format(new Date(doc.createdAt))}</span>
                          <span className="block text-xs text-ink-3">by {doc.uploadedBy}</span>
                        </td>
                        {isAdmin && (
                          <td className="px-4 py-3 text-right">
                            <button
                              type="button"
                              className="btn btn-quiet btn-sm"
                              aria-label={`Delete ${doc.title}`}
                              aria-expanded={confirming}
                              aria-controls={confirming ? `confirm-${doc.id}` : undefined}
                              onClick={() => setConfirmingId(confirming ? null : doc.id)}
                            >
                              <Trash2 className="size-4" aria-hidden="true" />
                              <span className="max-sm:sr-only">Delete</span>
                            </button>
                          </td>
                        )}
                      </tr>
                      {confirming && (
                        <tr>
                          <td colSpan={5} className="bg-danger-soft px-4 py-3">
                            <DeleteConfirm doc={doc} onCancel={() => setConfirmingId(null)} onDeleted={onDeleted} />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}

function UploadForm({ onUploaded }: { onUploaded: (doc: DocumentInfo) => void }) {
  const queryClient = useQueryClient()
  const formRef = useRef<HTMLFormElement>(null)
  const [errors, setErrors] = useState<{ file?: string; roles?: string }>({})
  const upload = useMutation({
    mutationFn: api.upload,
    onSuccess: (doc) => {
      formRef.current?.reset()
      onUploaded(doc)
      return queryClient.invalidateQueries({ queryKey: DOCUMENTS })
    },
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const file = data.get('file')
    const next = {
      file: file instanceof File && file.size > 0 ? undefined : 'Choose a file to upload.',
      roles: data.getAll('allowedRoles').length > 0 ? undefined : 'Choose at least one role that can read this document.',
    }
    setErrors(next)
    if (next.file) return form.querySelector<HTMLInputElement>('#upload-file')?.focus()
    if (next.roles) return form.querySelector<HTMLInputElement>('input[name="allowedRoles"]')?.focus()
    if (!String(data.get('title') ?? '').trim()) data.delete('title')
    upload.mutate(data)
  }

  return (
    <section aria-labelledby="upload-title" className="mt-8 max-w-3xl rounded-panel border border-rule bg-surface p-5 sm:p-6">
      <h2 id="upload-title" className="font-semibold">
        Upload a document
      </h2>
      <form ref={formRef} onSubmit={submit} noValidate className="mt-4 grid gap-5 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <label htmlFor="upload-file" className="text-sm font-semibold">
            File
          </label>
          <input
            id="upload-file"
            name="file"
            type="file"
            aria-invalid={errors.file ? true : undefined}
            aria-describedby={errors.file ? 'upload-file-error' : undefined}
            onChange={() => errors.file && setErrors({ ...errors, file: undefined })}
            className="mt-1.5 block w-full text-sm text-ink-2 file:mr-3 file:cursor-pointer file:rounded-tag file:border file:border-rule-strong file:bg-sunken file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-ink hover:file:bg-rule active:file:bg-rule-strong"
          />
          {errors.file && (
            <p id="upload-file-error" className="mt-1.5 text-sm text-danger">
              {errors.file}
            </p>
          )}
        </div>

        <div className="sm:col-span-2">
          <label htmlFor="upload-title" className="text-sm font-semibold">
            Title <span className="font-normal text-ink-3">(optional)</span>
          </label>
          <input id="upload-title" name="title" type="text" autoComplete="off" maxLength={200} className="field mt-1.5" />
        </div>

        <fieldset className="sm:col-span-2" aria-describedby={errors.roles ? 'upload-roles-error' : undefined}>
          <legend className="text-sm font-semibold">Who can read it</legend>
          <div className="mt-2 flex flex-wrap gap-2">
            {ROLES.map((role) => (
              <label
                key={role}
                className="flex cursor-pointer items-center gap-2 rounded-tag border border-rule-strong px-2.5 py-1.5 font-mono text-xs text-ink-2 select-none hover:border-ink-3 active:bg-sunken has-checked:border-accent has-checked:bg-accent-soft has-checked:text-ink has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent"
              >
                <input
                  type="checkbox"
                  name="allowedRoles"
                  value={role}
                  onChange={() => errors.roles && setErrors({ ...errors, roles: undefined })}
                  className="size-4 accent-accent focus-visible:outline-none"
                />
                <span translate="no">{role}</span>
              </label>
            ))}
          </div>
          {errors.roles && (
            <p id="upload-roles-error" className="mt-1.5 text-sm text-danger">
              {errors.roles}
            </p>
          )}
        </fieldset>

        <div className="flex flex-wrap items-center gap-4 sm:col-span-2">
          <button type="submit" className="btn btn-primary" disabled={upload.isPending}>
            {upload.isPending ? <Spinner /> : <Upload className="size-4" aria-hidden="true" />}
            {upload.isPending ? 'Uploading and indexing…' : 'Upload document'}
          </button>
          {upload.isSuccess && (
            <p className="text-sm text-ink-2">
              Uploaded “{upload.data.title}” as {upload.data.chunkCount} chunks.
            </p>
          )}
        </div>
        {upload.isError && (
          <div className="sm:col-span-2">
            <Notice title="Upload failed">{upload.error.message}</Notice>
          </div>
        )}
      </form>
    </section>
  )
}

interface DeleteConfirmProps {
  doc: DocumentInfo
  onCancel: () => void
  onDeleted: (doc: DocumentInfo) => void
}

function DeleteConfirm({ doc, onCancel, onDeleted }: DeleteConfirmProps) {
  const queryClient = useQueryClient()
  const cancelRef = useRef<HTMLButtonElement>(null)
  // Captured during the first render, while focus is still on the Delete button that opened this.
  const [returnTo] = useState(() => document.activeElement as HTMLElement | null)
  const remove = useMutation({
    mutationFn: () => api.deleteDocument(doc.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: DOCUMENTS })
      onDeleted(doc)
    },
  })

  useEffect(() => cancelRef.current?.focus(), [])

  function cancel() {
    onCancel()
    returnTo?.focus()
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape' && !remove.isPending) cancel()
  }

  return (
    <div
      id={`confirm-${doc.id}`}
      role="group"
      aria-label={`Confirm deleting ${doc.title}`}
      onKeyDown={onKeyDown}
      className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2"
    >
      <p className="min-w-0 text-sm break-words text-ink">
        Delete “{doc.title}” and its {doc.chunkCount} chunks? This cannot be undone.
      </p>
      <div className="ml-auto flex gap-2">
        <button ref={cancelRef} type="button" className="btn btn-quiet btn-sm" onClick={cancel} disabled={remove.isPending}>
          Cancel
        </button>
        <button type="button" className="btn btn-danger btn-sm" onClick={() => remove.mutate()} disabled={remove.isPending}>
          {remove.isPending && <Spinner />}
          {remove.isPending ? 'Deleting…' : 'Delete document'}
        </button>
      </div>
      {remove.isError && (
        <p role="alert" className="w-full text-sm text-danger">
          {remove.error.message}
        </p>
      )}
    </div>
  )
}
