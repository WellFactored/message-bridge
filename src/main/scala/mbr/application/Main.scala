package mbr.application

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.rabbitmq.client.{Channel, Connection, ConnectionFactory}
import mbr.rmq.ExchangePublisher
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
    for {
      connection <- createConnection(RabbitMQConfig())
      channel    <- createChannel(connection)
      publisher = new ExchangePublisher[IO](channel, "events")
      processor = new ProcessToRabbitMQ[IO](publisher)
      sqs <- Resource.make[IO, AmazonSQS](
              IO(AmazonSQSClientBuilder.standard().withCredentials(new ProfileCredentialsProvider("wellfactored")).withRegion("eu-west-1").build()))(
              sqs => IO(sqs.shutdown()))
      sqsQueue <- Resource.liftF(LiveSQSQueue[IO](sqs, "message-bridge-events"))
      poller = new LiveSQSPoller(sqsQueue, processor)
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
