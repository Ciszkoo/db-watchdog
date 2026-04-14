import { type RouteConfig, index, layout, route } from "@react-router/dev/routes"

export default [
  layout("routes/app-shell.tsx", [
    index("routes/home.tsx"),
    route("admin", "routes/admin-layout.tsx", [
      index("routes/admin-index.tsx"),
      route("databases", "routes/admin-databases.tsx"),
      route("access", "routes/admin-access.tsx"),
      route("sessions", "routes/admin-sessions.tsx"),
    ]),
  ]),
] satisfies RouteConfig
