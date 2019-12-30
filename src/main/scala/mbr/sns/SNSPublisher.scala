package mbr.sns

import cats.{Applicative, Defer, Monad}
import com.amazonaws.services.sns._
import com.amazonaws.services.sns.model._

import scala.jdk.CollectionConverters._

class SNSPublisher[F[_]: Defer: Monad](sns: AmazonSNSClient, topicName: String) {

  def publish(attributes: Map[String, MessageAttributeValue], body: String): F[Unit] = {
    val req = new PublishRequest(topicName, body).withMessageAttributes(attributes.asJava)
    delay(sns.publish(req))
  }

  private def delay[A](f: => A): F[A] =
    Defer[F].defer(Applicative[F].pure(f))
}
