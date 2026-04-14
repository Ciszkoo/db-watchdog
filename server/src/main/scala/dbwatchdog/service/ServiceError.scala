package dbwatchdog.service

sealed abstract class ServiceError(message: String)
    extends RuntimeException(message)

object ServiceError {
  final case class BadRequest(message: String) extends ServiceError(message)
  final case class Conflict(message: String) extends ServiceError(message)
  final case class Forbidden(message: String) extends ServiceError(message)
  final case class NotFound(message: String) extends ServiceError(message)
}
