package mbr.sns

import cats.effect.Sync
import cats.implicits._
import com.amazonaws.services.sns.{AmazonSNS, AmazonSNSClient}
import com.amazonaws.services.sns.model.{ListTopicsResult, Topic}
import fs2.{Chunk, Stream}
import mbr.sqs.SQSQueue

import scala.jdk.CollectionConverters._

trait SNSTopic[F[_]] {
  def arn:String
  def subscribe(sqsQueue: SQSQueue[F]): F[Unit]
}

object SNSTopic {
  private def _chunk[F[_]](ol: ListTopicsResult, sns: AmazonSNS): Chunk[SNSTopic[F]] =
    Chunk.seq(ol.getTopics.asScala.map(new LiveSNSTopic[F](sns, _)))

  def list[F[_]: Sync](sns: AmazonSNS): Stream[F, SNSTopic[F]] =
    Stream.unfoldChunkEval[F, () => Option[ListTopicsResult], SNSTopic[F]] {
      // Start with a function that provides the first batch of topics
      () =>
        Option(sns.listTopics())
    } { listTopics =>
      Sync[F].delay {
        listTopics().map { ltr =>
          if (ltr.getNextToken != null)
            (_chunk(ltr, sns), () => Option(sns.listTopics(ltr.getNextToken)))
          else
            (_chunk(ltr, sns), () => None)
        }
      }
    }
}

class LiveSNSTopic[F[_]](sns: AmazonSNS, topic: Topic) extends SNSTopic[F] {
  override def subscribe(sqsQueue: SQSQueue[F]): F[Unit] = ???

  def arn:String = topic.getTopicArn
}
