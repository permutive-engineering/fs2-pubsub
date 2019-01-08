package com.permutive.pubsub.producer.http

import scala.concurrent.duration._

case class PubsubHttpProducerConfig[F[_]](
  host: String = "pubsub.googleapis.com",
  port: Int = 443,
  isEmulator: Boolean = false,

  oauthTokenRefreshInterval: FiniteDuration = 30.minutes,
  onTokenRefreshError: PartialFunction[Throwable, F[Unit]] = PartialFunction.empty,
)
