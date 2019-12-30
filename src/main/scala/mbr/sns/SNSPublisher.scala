package mbr.sns

import cats.{Applicative, Defer, Monad}
import com.amazonaws.services.sns.AmazonSNS
import com.amazonaws.services.sns.model.PublishRequest

import scala.jdk.CollectionConverters._

class SNSPublisher[F[_]: Defer: Monad](sns: AmazonSNS, topicName: String) {

  def publish(messageData: SNSMessageData): F[Unit] = {
    val req = new PublishRequest(topicName, messageData.body).withMessageAttributes(messageData.attributes.asJava)
    delay(sns.publish(req))
  }

  private def delay[A](f: => A): F[A] =
    Defer[F].defer(Applicative[F].pure(f))
}
