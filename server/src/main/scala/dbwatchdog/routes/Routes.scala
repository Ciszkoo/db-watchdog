package dbwatchdog.routes

import cats.effect.IO
import cats.implicits.*
import org.http4s.HttpRoutes
import org.http4s.server.{AuthMiddleware, Router}

import dbwatchdog.auth.AuthUser
import dbwatchdog.service.{
  AccessService,
  AdminService,
  HealthService,
  UserService
}

object Routes {
  def all(
      userService: UserService,
      adminService: AdminService,
      accessService: AccessService
  )(using
      authMiddleware: AuthMiddleware[IO, AuthUser]
  ): HttpRoutes[IO] = Router(
    "/api/v1" -> (
      HealthService.routes <+>
        UserRoutes.routes(userService) <+>
        AdminRoutes.routes(adminService) <+>
        AccessRoutes.routes(accessService)
    )
  )
}
