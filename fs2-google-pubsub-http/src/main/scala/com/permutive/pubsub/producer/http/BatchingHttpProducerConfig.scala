package com.permutive.pubsub.producer.http

import scala.concurrent.duration.FiniteDuration

case class BatchingHttpProducerConfig(
  batchSize: Int,
  maxLatency: FiniteDuration,
  retryTimes: Int,
  retryInitialDelay: FiniteDuration,
  retryNextDelay: FiniteDuration => FiniteDuration
)
