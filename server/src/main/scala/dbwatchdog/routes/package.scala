package dbwatchdog

import java.util.UUID

import cats.effect.IO
import cats.implicits.*
import io.circe.Decoder
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.{Request, Response}

import dbwatchdog.auth.AuthUser
import dbwatchdog.service.ServiceError

package object routes {

  def parseUuid(
      raw: String,
      fieldName: String
  ): IO[UUID] =
    IO.fromEither(
      scala.util
        .Try(UUID.fromString(raw))
        .toEither
        .leftMap(_ => ServiceError.BadRequest(s"Invalid UUID for $fieldName"))
    )

  def decodeJson[A: Decoder](request: Request[IO]): IO[A] =
    request.attemptAs[A].value.flatMap {
      case Right(value) => IO.pure(value)
      case Left(_)      =>
        IO.raiseError(ServiceError.BadRequest("Malformed JSON"))
    }

  def dbaOnly(
      authUser: AuthUser
  )(run: => IO[Response[IO]]): IO[Response[IO]] =
    if authUser.isDba then run else Forbidden()

  def handleServiceErrors(
      run: IO[Response[IO]]
  ): IO[Response[IO]] =
    run.handleErrorWith {
      case ServiceError.BadRequest(message) => BadRequest(message)
      case ServiceError.Conflict(message)   => Conflict(message)
      case ServiceError.Forbidden(message)  => Forbidden(message)
      case ServiceError.NotFound(message)   => NotFound(message)
      case error => InternalServerError(s"Error: ${error.getMessage}")
    }
}
