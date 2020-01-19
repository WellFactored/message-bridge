package mbr.application

import cats.effect.{ConcurrentEffect, ContextShift}
import cats.implicits._
import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.services.sqs.AmazonSQS
import com.typesafe.scalalogging.StrictLogging
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
    sqs:     AmazonSQS,
    sns:     AmazonSNS,
    config:  BridgeConfig
  ): F[Stream[F, Unit]] = {
    import config._
    for {
      rabbitMQPipeline <- initializeRabbitConsumer(client, channel, sns, rmqExchangeName, snsTopicName)
      sqsPipeline      <- initializeSQSConsumer(client, channel, sqs, rmqExchangeName, snsTopicName)
    } yield rabbitMQPipeline.concurrently(sqsPipeline)
  }

  def initializeRabbitConsumer[F[_]: ContextShift: ConcurrentEffect](
    client:   RabbitClient[F],
    channel:  AMQPChannel,
    sns:      AmazonSNS,
    exchange: ExchangeName,
    topic:    String): F[Stream[F, Unit]] =
    for {
      (acker, messageStream) <- RabbitMQConsumer.build[F, String](channel, exchange, QueueName(s"${exchange.value}-message-bridge"), client)
      snsPublisher = new SNSPublisher[F](sns, s"arn:aws:sns:eu-west-1:079345157050:$topic")
      processor    = new RabbitMQMessageProcessor[F](acker, snsPublisher)
    } yield messageStream.evalMap(processor.process)

  def initializeSQSConsumer[F[_]: ContextShift: ConcurrentEffect](
    client:   RabbitClient[F],
    channel:  AMQPChannel,
    sqs:      AmazonSQS,
    exchange: ExchangeName,
    topic:    String): F[Stream[F, Unit]] =
    for {
      sqsQueue <- LiveSQSQueue[F](sqs, s"message-bridge-$topic")
      responder = new LiveSQSResponder[F](sqsQueue)
      processor = new ProcessToRabbitMQ[F](new ExchangePublisher[F](channel, exchange))
    } yield sqsQueue.stream(20, 2).evalMap(processor.apply).evalMap(responder.respond)
}
