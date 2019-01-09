package com.permutive.pubsub.consumer.grpc

import com.google.cloud.pubsub.v1.Subscriber

import scala.concurrent.duration._

/**
  * Pubsub subscriber config.
  *
  * @param maxQueueSize configures two options: the max size of the backing queue, and, the "max outstanding element count" option of Pubsub
  * @param parallelPullCount number of parallel pullers, see {@link com.google.cloud.pubsub.v1.Subscriber.Builder#setParallelPullCount(int) setParallelPullCount}
  * @param maxAckExtensionPeriod see {@link com.google.cloud.pubsub.v1.Subscriber.Builder#setMaxAckExtensionPeriod(org.threeten.bp.Duration) setMaxAckExtensionPeriod}
  * @param awaitTerminatePeriod if the underlying PubSub subcriber fails to terminate cleanly, how long do we wait until it's forcibly timed out.
  * @param onFailedTerminate upon failure to terminate, call this function
  * @param customizeSubscriber optionally, provide a function that allows full customisation of the underlying Java Subscriber object.
  */
case class PubsubGoogleConsumerConfig[F[_]](
  maxQueueSize: Int = 1000,

  parallelPullCount: Int = 3,
  maxAckExtensionPeriod: FiniteDuration = 10.seconds,

  awaitTerminatePeriod: FiniteDuration = 30.seconds,

  onFailedTerminate: Throwable => F[Unit],

  customizeSubscriber: Option[Subscriber.Builder => Subscriber.Builder] = None,
)
