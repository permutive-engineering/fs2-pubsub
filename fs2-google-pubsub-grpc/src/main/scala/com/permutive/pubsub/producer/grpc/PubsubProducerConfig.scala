package com.permutive.pubsub.producer.grpc

import com.google.cloud.pubsub.v1.Publisher

import scala.concurrent.duration._

case class PubsubProducerConfig[F[_]](
  batchSize: Long,
  delayThreshold: FiniteDuration,
  requestByteThreshold: Option[Long] = None,
  averageMessageSize: Long = 1024, // 1kB

  callbackExecutors: Int = Runtime.getRuntime.availableProcessors() * 3,
  // modify publisher
  customizePublisher: Option[Publisher.Builder => Publisher.Builder] = None,
  // termination
  awaitTerminatePeriod: FiniteDuration = 30.seconds,
  onFailedTerminate: Throwable => F[Unit],
)
