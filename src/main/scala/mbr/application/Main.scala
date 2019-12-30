package mbr.application

import cats.effect.{Blocker, ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.sns.{AmazonSNS, AmazonSNSClientBuilder}
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.rabbitmq.client.{Channel, Connection, ConnectionFactory}
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{ExchangeName, QueueName}
import mbr.rmq.{ExchangePublisher, RabbitMQConsumer, RabbitMQMessageProcessor}
import mbr.sns.SNSPublisher
import mbr.sqs.{LiveSQSPoller, LiveSQSQueue, ProcessToRabbitMQ}

final case class RabbitMQConfig(
  host:     String = "localhost",
  port:     Int    = 5672,
  vhost:    String = "/",
  username: String = "guest",
  password: String = "guest"
)

object Main extends IOApp {
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
        requeueOnNack = true,
        Some(10)
      )

    for {
      connection             <- createConnection(RabbitMQConfig())
      channel                <- createChannel(connection)
      blocker                <- Blocker[IO]
      client                 <- Resource.liftF(RabbitClient[IO](fs2RabbitConfig, blocker))
      (acker, messageStream) <- RabbitMQConsumer.build[IO, String](ExchangeName("events"), QueueName("events-message-bridge"), client)
      credentials = new ProfileCredentialsProvider("wellfactored")
      sns <- Resource.make[IO, AmazonSNS](IO(AmazonSNSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build()))(sns =>
              IO(sns.shutdown()))
      snsPublisher = new SNSPublisher[IO](sns, "events")
      _ <- Resource.liftF(messageStream.evalMap(new RabbitMQMessageProcessor[IO](acker, snsPublisher).process).compile.drain)

      sqs <- Resource.make[IO, AmazonSQS](IO(AmazonSQSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build()))(sqs =>
              IO(sqs.shutdown()))
      sqsQueue <- Resource.liftF(LiveSQSQueue[IO](sqs, "message-bridge-events"))
      processor = new ProcessToRabbitMQ[IO](new ExchangePublisher[IO](channel, "events"))
      poller    = new LiveSQSPoller(sqsQueue, processor)
      fiber <- Resource.liftF(poller.start)
    } yield fiber
  }.use { fiber =>
    fiber.join.as(ExitCode.Error)
  }

  def createConnection(config: RabbitMQConfig): Resource[IO, Connection] =
    Resource.make {
      val connectionFactory = new ConnectionFactory()
      connectionFactory.setHost(config.host)
      connectionFactory.setPort(config.port)
      connectionFactory.setVirtualHost(config.vhost)
      connectionFactory.setUsername(config.username)
      connectionFactory.setPassword(config.password)
      IO(connectionFactory.newConnection())
    }(connection => IO(connection.close()))

  def createChannel(connection: Connection): Resource[IO, Channel] =
    Resource.make(IO(connection.createChannel()))(channel => IO(channel.close()))
}
