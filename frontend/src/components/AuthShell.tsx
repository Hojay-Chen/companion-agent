import type { ReactNode } from 'react'

export default function AuthShell({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle?: string
  children: ReactNode
}) {
  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-cocoa-950 px-4">
      <div className="absolute -top-40 right-1/4 h-96 w-96 rounded-full bg-ember/10 blur-3xl" />
      <div className="absolute -bottom-20 left-1/4 h-80 w-80 rounded-full bg-rosewood/10 blur-3xl" />
      <div className="relative w-full max-w-sm">
        <div className="mb-8 text-center">
          <div className="mb-3 text-4xl">🤍</div>
          <h1 className="font-editorial text-3xl text-cocoa-50">{title}</h1>
          {subtitle && <p className="mt-2 text-sm text-cocoa-400">{subtitle}</p>}
        </div>
        <div className="card p-6">{children}</div>
      </div>
    </div>
  )
}
