import cats.data.Chain
import cats.mtl.FunctorTell
import ch.qos.logback.classic.Level

package object mbr {
  type Logger[F[_]] = FunctorTell[F, Chain[LogEntry]]

  case class LogEntry(level: Level, message: String, error: Option[Throwable])

  object Log {
    def debug[F[_]](message: String)(implicit logger: Logger[F]) = logger.tell(Chain.one(LogEntry(Level.DEBUG, message, None)))
    def info[F[_]](message: String)(implicit logger: Logger[F]) = logger.tell(Chain.one(LogEntry(Level.INFO, message, None)))
    def warn[F[_]](message: String)(implicit logger: Logger[F]) = logger.tell(Chain.one(LogEntry(Level.WARN, message, None)))
    def error[F[_]](message: String)(implicit logger: Logger[F]) = logger.tell(Chain.one(LogEntry(Level.ERROR, message, None)))
    def error[F[_]](message: String, t:               Throwable)(implicit logger: Logger[F]) = logger.tell(Chain.one(LogEntry(Level.ERROR, message, Some(t))))
  }

}
