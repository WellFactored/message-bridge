package mbr.application

import cats.Applicative
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Resource}
import cats.implicits._
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.rabbitmq.client.{Channel, Connection, ConnectionFactory}
import com.typesafe.scalalogging.StrictLogging
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.{AMQPChannel, ExchangeName, QueueName}
import fs2.Stream
import mbr.rmq.{ExchangePublisher, RabbitMQConsumer, RabbitMQMessageProcessor}
import mbr.sns.SNSPublisher
import mbr.sqs.{LiveSQSQueue, LiveSQSResponder, ProcessToRabbitMQ}

object Bridge extends StrictLogging {
  def build[F[_]: ContextShift: ConcurrentEffect](
    client:  RabbitClient[F],
    channel: AMQPChannel,
    sns:     AmazonSNS,
    config:  BridgeConfig
  ): F[Stream[F, Unit]] = {
    import config._
    for {
      rabbitMQPipeline <- initializeRabbitConsumer(client, channel, sns, rmqExchangeName, snsTopicName)
      //sqsPipeline      <- initializeSQSConsumer(fs2RabbitConfig, awsCredentialsProvider, rmqExchangeName, snsTopicName)
    } yield rabbitMQPipeline
  }

  def initializeRabbitConsumer[F[_]: ContextShift: ConcurrentEffect](
    client:   RabbitClient[F],
    channel:  AMQPChannel,
    sns:      AmazonSNS,
    exchange: ExchangeName,
    topic:    String): F[Stream[F, Unit]] =
    for {
      (acker, messageStream) <- RabbitMQConsumer.build[F, String](channel, exchange, QueueName(s"${exchange.value}-message-bridge"), client)
      snsPublisher            = new SNSPublisher[F](sns, s"arn:aws:sns:eu-west-1:079345157050:$topic")
      messageProcessingStream = messageStream.evalMap(new RabbitMQMessageProcessor[F](acker, snsPublisher).process)
    } yield messageProcessingStream

  def initializeSQSConsumer[F[_]: ContextShift: ConcurrentEffect](
    fs2RabbitConfig: Fs2RabbitConfig,
    credentials:     AWSCredentialsProvider,
    exchange:        ExchangeName,
    topic:           String): Resource[F, Stream[F, Unit]] =
    for {
      blocker <- Blocker[F]
      client  <- Resource.liftF(RabbitClient[F](fs2RabbitConfig, blocker))
      channel <- client.createConnectionChannel
      sqs <- Resource.make[F, AmazonSQS](AmazonSQSClientBuilder.standard().withCredentials(credentials).withRegion("eu-west-1").build().pure[F]) {
              sqs =>
                logger.info(s"shutting down sqs client")
                sqs.shutdown().pure[F]
            }
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
