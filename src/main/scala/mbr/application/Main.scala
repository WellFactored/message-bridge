package mbr.application

import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.sns.{AmazonSNS, AmazonSNSClientBuilder}
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{AMQPChannel, ExchangeName}
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

    //val topics = SNSTopic.list[IO](AmazonSNSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build)

    val configs: List[BridgeConfig] = List(
      BridgeConfig(ExchangeName("events"), "events", fs2RabbitConfig, credentials),
      BridgeConfig(ExchangeName("commands"), "commands", fs2RabbitConfig, credentials),
      BridgeConfig(ExchangeName("requests"), "requests", fs2RabbitConfig, credentials),
      BridgeConfig(ExchangeName("responses"), "responses", fs2RabbitConfig, credentials)
    )

    def bridges(sqs: AmazonSQS, sns: AmazonSNS, client: RabbitClient[IO], channel: AMQPChannel): IO[List[fs2.Stream[IO, Unit]]] =
      configs.traverse(config => Bridge.build[IO](client, channel, sqs, sns, config))

    case class ApplicationResources(sqs: AmazonSQS, sns: AmazonSNS, client: RabbitClient[IO], channel: AMQPChannel)

    val resources = for {
      blocker    <- Blocker[IO]
      client     <- Resource.liftF(RabbitClient.apply[IO](fs2RabbitConfig, blocker))
      connection <- client.createConnection
      channel    <- client.createChannel(connection)
      sns <- Resource.make[IO, AmazonSNS](IO(AmazonSNSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build())) { sns =>
              IO(sns.shutdown())
            }
      sqs <- Resource.make[IO, AmazonSQS](IO(AmazonSQSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build())) { sqs =>
              logger.info(s"shutting down sqs client") >>
                IO(sqs.shutdown())
            }
    } yield ApplicationResources(sqs, sns, client, channel)

    resources
      .use { res =>
        bridges(res.sqs, res.sns, res.client, res.channel)
          .flatMap {
            case Nil =>
              Stream.eval(logger.warn("No bridges are configured - exiting")).compile.drain

            case head :: tail => tail.fold(head)(_.concurrently(_)).compile.drain
          }
      }
      .as(ExitCode.Error)
  }
}
