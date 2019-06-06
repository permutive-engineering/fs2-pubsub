package com.permutive.pubsub.consumer.http

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

case class PubsubMessage(
  data: String,
  attributes: Map[String, String],
  messageId: String,
  publishTime: String,
)

object PubsubMessage {
  implicit final val Codec: JsonValueCodec[PubsubMessage] =
    JsonCodecMaker.make[PubsubMessage](CodecMakerConfig())
}
