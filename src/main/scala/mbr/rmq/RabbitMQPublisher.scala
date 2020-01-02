package mbr.rmq

import cats.effect.Sync
import cats.{Applicative, Defer}
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.Channel
import mbr.application.EffectfulLogging

trait RabbitMQPublisher[F[_]] {
  def publish(routingKey: String, properties: BasicProperties, body: String): F[Unit]
}

class ExchangePublisher[F[_]: Defer: Sync](channel: Channel, exchangeName: String) extends RabbitMQPublisher[F] with EffectfulLogging[F] {
  override def publish(routingKey: String, properties: BasicProperties, body: String): F[Unit] = {
    delay(channel.basicPublish(exchangeName, routingKey, properties, body.getBytes))
  }

  def delay[A](f: => A): F[A] =
    Defer[F].defer(Applicative[F].pure(f))
}
