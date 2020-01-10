package mbr.application

import cats.effect.{Blocker, ContextShift, IO, Resource}
import cats.implicits._
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.sns.{AmazonSNS, AmazonSNSClientBuilder}
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.rabbitmq.client.{Channel, Connection, ConnectionFactory}
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{ExchangeName, QueueName}
import fs2.Stream
import mbr.rmq.{ExchangePublisher, RabbitMQConsumer, RabbitMQMessageProcessor}
import mbr.sns.SNSPublisher
import mbr.sqs.{LiveSQSQueue, LiveSQSResponder, ProcessToRabbitMQ}

object Bridge {
  def build(config: BridgeConfig)(implicit cs: ContextShift[IO]): Resource[IO, Stream[IO, Unit]] = {
    import config._
    for {
      rabbitMQPipeline <- initializeRabbitConsumer(fs2RabbitConfig, awsCredentialsProvider, rmqExchangeName, snsTopicName)
      sqsPipeline      <- initializeSQSConsumer(RabbitMQConfig.from(fs2RabbitConfig), awsCredentialsProvider, rmqExchangeName, snsTopicName)
    } yield rabbitMQPipeline.concurrently(sqsPipeline)
  }

  def initializeRabbitConsumer(fs2RabbitConfig: Fs2RabbitConfig, credentials: AWSCredentialsProvider, exchange: ExchangeName, topic: String)(
    implicit cs:                                ContextShift[IO]): Resource[IO, Stream[IO, Unit]] =
    for {
      blocker                <- Blocker[IO]
      client                 <- Resource.liftF(RabbitClient[IO](fs2RabbitConfig, blocker))
      (acker, messageStream) <- RabbitMQConsumer.build[IO, String](exchange, QueueName(s"${exchange.value}-message-bridge"), client)
      sns <- Resource.make[IO, AmazonSNS](IO(AmazonSNSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build()))(sns =>
              IO(sns.shutdown()))
      snsPublisher = new SNSPublisher[IO](sns, s"arn:aws:sns:eu-west-1:079345157050:$topic")
      messageProcessingStream <- Resource.pure[IO, Stream[IO, Unit]](
                                  messageStream.evalMap(new RabbitMQMessageProcessor[IO](acker, snsPublisher).process))
    } yield messageProcessingStream

  def initializeSQSConsumer(
    rabbitMQConfig: RabbitMQConfig,
    credentials:    AWSCredentialsProvider,
    exchange:       ExchangeName,
    topic:          String): Resource[IO, Stream[IO, Unit]] =
    for {
      connection <- createConnection(rabbitMQConfig)
      channel    <- createChannel(connection)
      sqs <- Resource.make[IO, AmazonSQS](IO(AmazonSQSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build()))(sqs =>
              IO(sqs.shutdown()))
      sqsQueue <- Resource.liftF(LiveSQSQueue[IO](sqs, s"message-bridge-$topic"))
      responder = new LiveSQSResponder[IO](sqsQueue)
      processor = new ProcessToRabbitMQ[IO](new ExchangePublisher[IO](channel, exchange))
    } yield sqsQueue.stream(20, 2).evalMap(processor.apply).evalMap(responder.respond)

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
