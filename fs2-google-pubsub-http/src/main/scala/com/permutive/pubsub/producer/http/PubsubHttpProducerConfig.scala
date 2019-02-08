package com.permutive.pubsub.producer.http

import scala.concurrent.duration._

/**
  * Configuration for the PubSub HTTP producer.
  *
  * @param host                              host of PubSub
  * @param port                              port of PubSub
  * @param isEmulator                        whether the target PubSub is an emulator or not
  * @param oauthTokenRefreshInterval         how often to refresh the Google OAuth token
  * @param onTokenRefreshError               what to do if the token refresh fails
  * @param oauthTokenFailureRetryDelay       initial delay for retrying OAuth token retrieval
  * @param oauthTokenFailureRetryNextDelay   next delay for retrying OAuth token retrieval
  * @param oauthTokenFailureRetryMaxAttempts how many times to attempt
  */
case class PubsubHttpProducerConfig[F[_]](
  host: String = "pubsub.googleapis.com",
  port: Int = 443,
  isEmulator: Boolean = false,

  oauthTokenRefreshInterval: FiniteDuration = 30.minutes,
  onTokenRefreshError: PartialFunction[Throwable, F[Unit]] = PartialFunction.empty,

  oauthTokenFailureRetryDelay: FiniteDuration = 0.millis,
  oauthTokenFailureRetryNextDelay: FiniteDuration => FiniteDuration = _ => 5.minutes,
  oauthTokenFailureRetryMaxAttempts: Int = Int.MaxValue,
)
