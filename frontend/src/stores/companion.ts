import { create } from 'zustand'
import type { Companion } from '@/types'
import { api } from '@/api/client'

interface CompanionState {
  companions: Companion[]
  loading: boolean
  load: () => Promise<void>
  add: (c: Companion) => void
  remove: (id: string) => void
}

export const useCompanionStore = create<CompanionState>((set) => ({
  companions: [],
  loading: false,

  load: async () => {
    set({ loading: true })
    try {
      const list = await api.get<Companion[]>('/api/companions')
      set({ companions: list })
    } finally {
      set({ loading: false })
    }
  },

  add: (c) => set((s) => ({ companions: [...s.companions, c] })),
  remove: (id) => set((s) => ({ companions: s.companions.filter((c) => c.id !== id) })),
}))
