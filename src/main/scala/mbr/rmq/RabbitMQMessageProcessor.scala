package mbr
package rmq

import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import dev.profunktor.fs2rabbit.model.{AckResult, AmqpEnvelope}
import mbr.converters.RabbitMQToSNSConverter
import mbr.sns.SNSPublisher

import scala.util.control.NonFatal

class RabbitMQMessageProcessor[F[_]: Sync: MonadError[*[_], Throwable]](acker: RabbitMQConsumer.AckerFunction[F], publisher: SNSPublisher[F])(
  implicit logger: Logger[F]) {

  def process(envelope: AmqpEnvelope[String]): F[Unit] = {
    val messageData = new RabbitMQToSNSConverter(envelope)

    Log.debug(s"Processing message $envelope") >> {
      if (messageData.alreadyBridged) Log.debug("dropping message that has already been bridged")
      else publisher.publish(messageData)
    } >>
      acker(AckResult.Ack(envelope.deliveryTag))
  }.recoverWith {
    case NonFatal(t) =>
      Log.error("Error trying to republish message to rabbitmq", t) >>
        acker(AckResult.NAck(envelope.deliveryTag))
  }
}
