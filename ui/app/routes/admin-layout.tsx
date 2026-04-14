import { Outlet } from "react-router"

import { useAuth } from "~/auth/AuthContext"
import { AccessDeniedCard } from "~/routes/app-shell"

export default function AdminLayout() {
  const { isDba } = useAuth()

  if (!isDba) {
    return <AccessDeniedCard />
  }

  return <Outlet />
}
