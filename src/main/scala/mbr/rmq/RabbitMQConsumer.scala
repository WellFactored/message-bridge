package mbr.rmq

import cats.Monad
import cats.effect.{Bracket, Resource}
import cats.implicits._
import dev.profunktor.fs2rabbit.config.declaration.{DeclarationQueueConfig, Durable, NonAutoDelete, NonExclusive}
import dev.profunktor.fs2rabbit.effects.EnvelopeDecoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model._
import fs2.Stream

object RabbitMQConsumer {

  type AckerFunction[F[_]]    = AckResult => F[Unit]
  type MessageStream[F[_], A] = Stream[F, AmqpEnvelope[A]]

  /**
    * This will declare the queue with appropriate parameters, bind it to the exchange and return
    * an fs2rabbit stream along with an acker function for messages on that stream
    */
  def build[F[_]: Monad, A](exchangeName: ExchangeName, queueName: QueueName, R: RabbitClient[F])(
    implicit
    decoder: EnvelopeDecoder[F, A],
    bracket: Bracket[F, Throwable]
  ): Resource[F, (AckerFunction[F], MessageStream[F, A])] =
    R.createConnectionChannel.flatMap { implicit channel =>
      Resource.liftF {
        R.declareQueue(DeclarationQueueConfig(queueName, Durable, NonExclusive, NonAutoDelete, Map.empty)) >>
          R.bindQueue(queueName, exchangeName, RoutingKey("#")) >>
          R.createAckerConsumer[A](queueName)
      }
    }
}
