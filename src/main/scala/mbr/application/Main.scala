package mbr.application

import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.model.ExchangeName

final case class RabbitMQConfig(
  host:     String = "localhost",
  port:     Int    = 5672,
  vhost:    String = "/",
  username: String = "guest",
  password: String = "guest"
)

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

    Bridge
      .build(fs2RabbitConfig, credentials, ExchangeName("events"), "events")
      .use(_.compile.drain)
      .as(ExitCode.Success)
  }
}
