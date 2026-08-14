import { create } from 'zustand'
import type { User } from '@/types'
import { api, getToken, setToken, clearToken } from '@/api/client'

interface AuthState {
  token: string | null
  user: User | null
  login: (username: string, password: string) => Promise<void>
  register: (data: { username: string; password: string; nickname?: string }) => Promise<void>
  fetchMe: () => Promise<void>
  logout: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  token: getToken(),
  user: null,

  login: async (username, password) => {
    const resp = await api.post<{ token: string; user: User }>('/api/auth/login', { username, password })
    setToken(resp.token)
    set({ token: resp.token, user: resp.user })
  },

  register: async (data) => {
    const resp = await api.post<{ token: string; user: User }>('/api/auth/register', data)
    setToken(resp.token)
    set({ token: resp.token, user: resp.user })
  },

  fetchMe: async () => {
    try {
      const me = await api.get<User>('/api/auth/me')
      set({ user: me })
    } catch {
      clearToken()
      set({ token: null, user: null })
    }
  },

  logout: () => {
    clearToken()
    set({ token: null, user: null })
  },
}))
