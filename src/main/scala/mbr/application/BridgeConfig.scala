package mbr.application

import com.amazonaws.auth.AWSCredentialsProvider
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.model.ExchangeName

case class BridgeConfig(
  rmqExchangeName: ExchangeName,
  snsTopicName:    String,
  fs2RabbitConfig: Fs2RabbitConfig,
  awsCredentialsProvider: AWSCredentialsProvider
)
