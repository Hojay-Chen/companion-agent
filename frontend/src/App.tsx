import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import Login from '@/pages/Login'
import Register from '@/pages/Register'
import Companions from '@/pages/Companions'
import CompanionCreate from '@/pages/CompanionCreate'
import Chat from '@/pages/Chat'
import Settings from '@/pages/Settings'
import type { ReactNode } from 'react'

function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      <Route
        path="/"
        element={
          <RequireAuth>
            <Companions />
          </RequireAuth>
        }
      />
      <Route
        path="/companions"
        element={
          <RequireAuth>
            <Companions />
          </RequireAuth>
        }
      />
      <Route
        path="/companions/new"
        element={
          <RequireAuth>
            <CompanionCreate />
          </RequireAuth>
        }
      />
      <Route
        path="/companions/:id"
        element={
          <RequireAuth>
            <Chat />
          </RequireAuth>
        }
      />
      <Route
        path="/companions/:id/settings"
        element={
          <RequireAuth>
            <Settings />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
