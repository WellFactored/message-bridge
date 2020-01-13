package mbr.application

import cats.Applicative
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Resource, Sync}
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
  def build[F[_]: ContextShift: ConcurrentEffect](config: BridgeConfig): Resource[F, Stream[F, Unit]] = {
    import config._
    for {
      rabbitMQPipeline <- initializeRabbitConsumer(fs2RabbitConfig, awsCredentialsProvider, rmqExchangeName, snsTopicName)
      sqsPipeline      <- initializeSQSConsumer(RabbitMQConfig.from(fs2RabbitConfig), awsCredentialsProvider, rmqExchangeName, snsTopicName)
    } yield rabbitMQPipeline.concurrently(sqsPipeline)
  }

  def initializeRabbitConsumer[F[_]: ContextShift: ConcurrentEffect](
    fs2RabbitConfig: Fs2RabbitConfig,
    credentials:     AWSCredentialsProvider,
    exchange:        ExchangeName,
    topic:           String): Resource[F, Stream[F, Unit]] =
    for {
      blocker                <- Blocker[F]
      client                 <- Resource.liftF(RabbitClient[F](fs2RabbitConfig, blocker))
      (acker, messageStream) <- RabbitMQConsumer.build[F, String](exchange, QueueName(s"${exchange.value}-message-bridge"), client)
      sns <- Resource.make[F, AmazonSNS](AmazonSNSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build().pure[F])(
              sns => sns.shutdown().pure[F])
      snsPublisher = new SNSPublisher[F](sns, s"arn:aws:sns:eu-west-1:079345157050:$topic")
      messageProcessingStream <- Resource.pure[F, Stream[F, Unit]](
                                  messageStream.evalMap(new RabbitMQMessageProcessor[F](acker, snsPublisher).process))
    } yield messageProcessingStream

  def initializeSQSConsumer[F[_]: Sync](
    rabbitMQConfig: RabbitMQConfig,
    credentials:    AWSCredentialsProvider,
    exchange:       ExchangeName,
    topic:          String): Resource[F, Stream[F, Unit]] =
    for {
      connection <- createConnection[F](rabbitMQConfig)
      channel    <- createChannel[F](connection)
      sqs <- Resource.make[F, AmazonSQS](AmazonSQSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build().pure[F])(
              sqs => sqs.shutdown().pure[F])
      sqsQueue <- Resource.liftF(LiveSQSQueue[F](sqs, s"message-bridge-$topic"))
      responder = new LiveSQSResponder[F](sqsQueue)
      processor = new ProcessToRabbitMQ[F](new ExchangePublisher[F](channel, exchange))
    } yield sqsQueue.stream(20, 2).evalMap(processor.apply).evalMap(responder.respond)

  def createConnection[F[_]: Applicative](config: RabbitMQConfig): Resource[F, Connection] =
    Resource.make {
      val connectionFactory = new ConnectionFactory()
      connectionFactory.setHost(config.host)
      connectionFactory.setPort(config.port)
      connectionFactory.setVirtualHost(config.vhost)
      connectionFactory.setUsername(config.username)
      connectionFactory.setPassword(config.password)
      connectionFactory.newConnection().pure[F]
    }(connection => connection.close().pure[F])

  def createChannel[F[_]: Applicative](connection: Connection): Resource[F, Channel] =
    Resource.make(connection.createChannel().pure[F])(channel => channel.close().pure[F])
}
