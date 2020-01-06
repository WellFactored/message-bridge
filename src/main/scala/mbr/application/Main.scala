package mbr.application

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.model.ExchangeName
import fs2.Stream

final case class RabbitMQConfig(
  host:     String = "localhost",
  port:     Int    = 5672,
  vhost:    String = "/",
  username: String = "guest",
  password: String = "guest"
)

object RabbitMQConfig {
  def from(fs2RabbitConfig: Fs2RabbitConfig): RabbitMQConfig = {
    import fs2RabbitConfig._
    RabbitMQConfig(
      nodes.head.host,
      nodes.head.port,
      virtualHost,
      username.getOrElse("guest"),
      password.getOrElse("guest")
    )
  }
}

object Main extends IOApp with IOLogging {
  override def run(args: List[String]): IO[ExitCode] = {
    val fs2RabbitConfig: Fs2RabbitConfig =
      Fs2RabbitConfig(
        "localhost",
        5672,
        "/",
        1000,
        ssl = false,
        Some("guest"),
        Some("guest"),
        requeueOnNack = false, // message will deadletter if the exchange is set up to do that
        Some(10)
      )

    val credentials = new ProfileCredentialsProvider("wellfactored")

    val configs = List(
      BridgeConfig(ExchangeName("events"), "events", fs2RabbitConfig, credentials),
      BridgeConfig(ExchangeName("commands"), "commands", fs2RabbitConfig, credentials)
    )

    val bridges: Resource[IO, List[fs2.Stream[IO, Unit]]] = configs.map(Bridge.build).sequence
    bridges
      .map {
        case Nil          => Stream.empty
        case head :: tail => tail.fold(head)(_.concurrently(_))
      }
      .use(_.compile.drain)
      .as(ExitCode.Error)
  }
}
