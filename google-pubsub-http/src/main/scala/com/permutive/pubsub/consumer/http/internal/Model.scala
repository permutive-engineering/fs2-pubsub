package com.permutive.pubsub.consumer.http.internal

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.permutive.pubsub.consumer.http.PubsubMessage
import com.permutive.pubsub.consumer.{Model => PublicModel}

private[http] object Model {
  case class ProjectNameSubscription(value: String) extends AnyVal
  object ProjectNameSubscription {
    def of(projectId: PublicModel.ProjectId, subscription: PublicModel.Subscription): ProjectNameSubscription = {
      ProjectNameSubscription(s"projects/${projectId.value}/subscriptions/${subscription.value}")
    }
  }
  case class Record[F[_]](value: PubsubMessage, ack: F[Unit], nack: F[Unit])

  final implicit val PullRequestCodec: JsonValueCodec[PullRequest] =
    JsonCodecMaker.make[PullRequest](CodecMakerConfig())

  final implicit val PullResponseCodec: JsonValueCodec[PullResponse] =
    JsonCodecMaker.make[PullResponse](CodecMakerConfig())

  final implicit val AckRequestCodec: JsonValueCodec[AckRequest] =
    JsonCodecMaker.make[AckRequest](CodecMakerConfig())

  final implicit val NackRequestCodec: JsonValueCodec[NackRequest] =
    JsonCodecMaker.make[NackRequest](CodecMakerConfig())

  case class AckId(value: String) extends AnyVal

  case class PullRequest(
    returnImmediately: Boolean,
    maxMessages: Int,
  )

  case class PullResponse(
    receivedMessages: List[ReceivedMessage],
  )

  case class ReceivedMessage(
    ackId: AckId,
    message: PubsubMessage,
  )

  case class AckRequest(
    ackIds: List[AckId]
  )

  case class NackRequest(
    ackIds: List[AckId],
    ackDeadlineSeconds: Int,
  )
}
