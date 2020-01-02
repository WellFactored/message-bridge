package mbr.application

import cats.effect.{IO, Sync}
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

/**
  * log4cats provides a logging interface that is abstracted within an effect type `F`. So
  * no more manually wrapping all your logging calls in `IO`!
  */
trait EffectfulLogging[F[_]] {
  def logger(implicit sync: Sync[F]): Logger[F] =
    Slf4jLogger.getLoggerFromClass[F](this.getClass)
}


trait IOLogging extends EffectfulLogging[IO] {
  lazy val logger: Logger[IO] =
    Slf4jLogger.getLoggerFromClass(this.getClass)
}

