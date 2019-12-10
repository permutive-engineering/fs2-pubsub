package com.permutive.pubsub.producer.http

import scala.concurrent.duration._

/**
  * Configuration for the PubSub HTTP producer.
  *
  * Note: It is up to the user to raise exceptions and terminate the lifecyle of services if this is desired when
  * retries are exhausted. Token refreshing runs on a background fiber so raising exceptions in
  * `onTokenRetriesExhausted` will have _no_ effect to the main fibers, in fact errors are swallowed entirely.
  *
  * @param host                              host of PubSub
  * @param port                              port of PubSub
  * @param isEmulator                        whether the target PubSub is an emulator or not
  * @param oauthTokenRefreshInterval         how often to refresh the Google OAuth token
  * @param onTokenRefreshError               callback to execute if refreshing the token fails during retries, errors rethrown and retried
  * @param oauthTokenFailureRetryDelay       initial delay for retrying OAuth token retrieval
  * @param oauthTokenFailureRetryNextDelay   next delay for retrying OAuth token retrieval
  * @param oauthTokenFailureRetryMaxAttempts how many times to attempt; will raise the last error once reached
  * @param onTokenRetriesExhausted           callback to execute if refreshing the token exhausts retries.
  *                                          See note above on exceptions, up to the *user* to raise exceptions in their
  *                                          service using this callback if required.
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
  onTokenRetriesExhausted: PartialFunction[Throwable, F[Unit]] = PartialFunction.empty,
)
