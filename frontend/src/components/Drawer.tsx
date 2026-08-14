import { X } from 'lucide-react'
import type { ReactNode } from 'react'

export default function Drawer({
  open,
  onClose,
  title,
  children,
}: {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
}) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-40">
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm animate-fadeIn" onClick={onClose} />
      <aside className="absolute right-0 top-0 flex h-full w-full max-w-md flex-col border-l border-cocoa-700 bg-cocoa-900 shadow-panel animate-fadeIn">
        <div className="flex items-center justify-between border-b border-cocoa-800 px-5 py-4">
          <h3 className="font-editorial text-lg text-cocoa-100">{title}</h3>
          <button
            onClick={onClose}
            className="rounded-lg p-1.5 text-cocoa-400 hover:bg-cocoa-800 hover:text-cocoa-100 transition"
          >
            <X size={18} />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
      </aside>
    </div>
  )
}
