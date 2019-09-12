package com.permutive.pubsub.http.oauth

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

final case class AccessToken(accessToken: String, tokenType: String, expiresIn: Int)

object AccessToken {
  implicit final val codec: JsonValueCodec[AccessToken] =
    JsonCodecMaker.make[AccessToken](
      CodecMakerConfig(
        fieldNameMapper = JsonCodecMaker.enforce_snake_case
      )
    )
}
