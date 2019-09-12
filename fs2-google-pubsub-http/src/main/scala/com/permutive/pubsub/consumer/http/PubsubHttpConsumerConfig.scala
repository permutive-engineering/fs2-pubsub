package com.permutive.pubsub.consumer.http

import scala.concurrent.duration._

case class PubsubHttpConsumerConfig[F[_]](
  host: String = "pubsub.googleapis.com",
  port: Int = 443,
  isEmulator: Boolean = false,
  oauthTokenRefreshInterval: FiniteDuration = 30.minutes,
  onTokenRefreshError: PartialFunction[Throwable, F[Unit]] = PartialFunction.empty,
  acknowledgeBatchSize: Int = 100,
  acknowledgeBatchLatency: FiniteDuration = 1.second,
  readReturnImmediately: Boolean = false,
  readMaxMessages: Int = 1000,
  readConcurrency: Int = 1
)
