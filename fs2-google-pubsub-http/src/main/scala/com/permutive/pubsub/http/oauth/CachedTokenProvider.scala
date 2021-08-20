package com.permutive.pubsub.http.oauth

import cats.effect.{Concurrent, Resource, Timer}
import com.permutive.pubsub.http.util.RefCache

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object CachedTokenProvider {

  /**
    * Generate a cached token provider from an underlying provider.
    *
    * @param underlying            the underlying token provider to use when a new token is required
    * @param safetyPeriod          how much time less than the indicated expiry to cache a token for; this is to give a
    *                              safety buffer to ensure an expired token is never used in a request
    * @param backgroundFailureHook hook called if the background fiber refreshing the token fails
    * @param onNewToken            a callback invoked whenever a new token is generated, the [[FiniteDuration]] is the
    *                              period that will be waited before the next new token
    */
  def resource[F[_]: Concurrent: Timer](
    underlying: TokenProvider[F],
    safetyPeriod: FiniteDuration,
    backgroundFailureHook: PartialFunction[Throwable, F[Unit]],
    onNewToken: Option[(AccessToken, FiniteDuration) => F[Unit]] = None,
  ): Resource[F, TokenProvider[F]] = {
    val cacheDuration: AccessToken => FiniteDuration = token =>
      // GCP access token lifetimes are specified in seconds.
      // If this is a negative amount then the sleep in `RefCache` will be for no time, it will not error.
      FiniteDuration(token.expiresIn.toLong, TimeUnit.SECONDS) - safetyPeriod

    RefCache
      .resource(underlying.accessToken, cacheDuration, backgroundFailureHook, onNewValue = onNewToken)
      .map(TokenProvider.instance)
  }

}
