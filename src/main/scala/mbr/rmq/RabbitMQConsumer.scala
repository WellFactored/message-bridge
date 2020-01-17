package mbr.rmq

import cats.Monad
import cats.effect.Bracket
import cats.implicits._
import dev.profunktor.fs2rabbit.config.declaration._
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
  def build[F[_]: Monad, A](channel: AMQPChannel, exchangeName: ExchangeName, queueName: QueueName, R: RabbitClient[F])(
    implicit
    decoder: EnvelopeDecoder[F, A],
    bracket: Bracket[F, Throwable]
  ): F[(AckerFunction[F], MessageStream[F, A])] = {
    implicit val c: AMQPChannel = channel
    val config: DeclarationExchangeConfig =
      DeclarationExchangeConfig(exchangeName, ExchangeType.Topic, Durable, NonAutoDelete, NonInternal, Map.empty)
    R.declareExchange(config) >>
      R.declareQueue(DeclarationQueueConfig(queueName, Durable, NonExclusive, NonAutoDelete, Map.empty)) >>
      R.bindQueue(queueName, exchangeName, RoutingKey("#")) >>
      R.createAckerConsumer[A](queueName)
  }
}
