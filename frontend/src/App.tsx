import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import Login from '@/pages/Login'
import Companions from '@/pages/Companions'
import CompanionCreate from '@/pages/CompanionCreate'
import Chat from '@/pages/Chat'
import ApplicationMarket from '@/pages/ApplicationMarket'
import ApplicationDetail from '@/pages/ApplicationDetail'
import AppSession from '@/pages/AppSession'
import JoinSession from '@/pages/JoinSession'
import Settings from '@/pages/Settings'
import type { ReactNode } from 'react'

// 内置应用的登记 —— 必须在任何页面渲染之前跑完。放在 App 里是刻意的:
// 它是"这份界面认得哪些应用"这件事的入口, 而不是某个页面的局部依赖。
import '@/apps'

function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token)
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      {/* 注册功能已关闭: /register 重定向到登录 */}
      <Route path="/register" element={<Navigate to="/login" replace />} />

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

      {/* 应用生态 (§16 那条路: 市场 → 详情 → 会话) */}
      <Route
        path="/applications"
        element={
          <RequireAuth>
            <ApplicationMarket />
          </RequireAuth>
        }
      />
      <Route
        path="/applications/:applicationId"
        element={
          <RequireAuth>
            <ApplicationDetail />
          </RequireAuth>
        }
      />
      <Route
        path="/applications/:applicationId/sessions/:sessionId"
        element={
          <RequireAuth>
            <AppSession />
          </RequireAuth>
        }
      />
      {/*
        只有 sessionId 的那条路 —— 从分享链接兑票进来时走这里。
        会话自己知道它是什么应用(§16 的 `application.id`), 所以路径里不必再带一次。
      */}
      <Route
        path="/sessions/:sessionId"
        element={
          <RequireAuth>
            <AppSession />
          </RequireAuth>
        }
      />
      {/* 分享链接本身。这条路径是公开的(持票即入), 但仍然要求登录 —— 票认的是"谁" */}
      <Route
        path="/join/:token"
        element={
          <RequireAuth>
            <JoinSession />
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
