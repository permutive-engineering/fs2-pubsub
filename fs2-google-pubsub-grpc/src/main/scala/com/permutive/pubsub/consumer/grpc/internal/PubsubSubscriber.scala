/*
 * Copyright 2018 Permutive
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.permutive.pubsub.consumer.grpc.internal

import cats.Applicative
import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all._
import com.google.api.core.ApiService
import com.google.api.gax.batching.FlowControlSettings
import com.google.cloud.pubsub.v1.{AckReplyConsumer, MessageReceiver, Subscriber}
import com.google.common.util.concurrent.MoreExecutors
import com.google.pubsub.v1.{ProjectSubscriptionName, PubsubMessage}
import com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumer.InternalPubSubError
import com.permutive.pubsub.consumer.grpc.PubsubGoogleConsumerConfig
import com.permutive.pubsub.consumer.{Model => PublicModel}
import fs2.{Chunk, Stream}
import org.threeten.bp.Duration

import java.util
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}
import scala.jdk.CollectionConverters._
import scala.collection.mutable.Builder

private[consumer] object PubsubSubscriber {

  def createSubscriber[F[_]: Sync](
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
    queue: BlockingQueue[Either[InternalPubSubError, Model.Record[F]]],
  ): Resource[F, ApiService] =
    Resource.make(
      Sync[F].delay {
        val receiver         = new PubsubMessageReceiver(queue)
        val subscriptionName = ProjectSubscriptionName.of(projectId.value, subscription.value)

        // build subscriber with "normal" settings
        val builder =
          Subscriber
            .newBuilder(subscriptionName, receiver)
            .setFlowControlSettings(
              FlowControlSettings
                .newBuilder()
                .setMaxOutstandingElementCount(config.maxQueueSize.toLong)
                .build()
            )
            .setParallelPullCount(config.parallelPullCount)
            .setMaxAckExtensionPeriod(Duration.ofMillis(config.maxAckExtensionPeriod.toMillis))

        // if provided, use subscriber transformer to modify subscriber
        val sub =
          config.customizeSubscriber
            .map(f => f(builder))
            .getOrElse(builder)
            .build()

        sub.addListener(new PubsubErrorListener(queue), MoreExecutors.directExecutor)

        sub.startAsync()
      }
    )(service =>
      Sync[F]
        .blocking(service.stopAsync().awaitTerminated(config.awaitTerminatePeriod.toSeconds, TimeUnit.SECONDS))
        .handleErrorWith(config.onFailedTerminate)
    )

  class PubsubMessageReceiver[F[_]: Sync, L](queue: BlockingQueue[Either[L, Model.Record[F]]]) extends MessageReceiver {
    override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit =
      queue.put(Right(Model.Record(message, Sync[F].delay(consumer.ack()), Sync[F].delay(consumer.nack()))))
  }

  class PubsubErrorListener[R](queue: BlockingQueue[Either[InternalPubSubError, R]]) extends ApiService.Listener {
    override def failed(from: ApiService.State, failure: Throwable): Unit =
      queue.put(Left(InternalPubSubError(failure)))
  }

  def takeNextElements[F[_]: Sync, A](messages: BlockingQueue[A]): F[Chunk[A]] =
    for {
      nextOpt <- Sync[F].delay(messages.poll()) // `poll` is non-blocking, returning `null` if queue is empty
      // `take` can wait for an element
      next <-
        if (nextOpt == null) Sync[F].interruptibleMany(messages.take())
        else Applicative[F].pure(nextOpt)
      chunk <- Sync[F].delay {
        val c = Wrapper.empty[A]
        c.add(next)
        messages.drainTo(c)

        c.toChunk
      }
    } yield chunk

  def subscribe[F[_]: Sync](
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
  ): Stream[F, Model.Record[F]] =
    for {
      queue <- Stream.eval(
        Sync[F].delay(new LinkedBlockingQueue[Either[InternalPubSubError, Model.Record[F]]](config.maxQueueSize))
      )
      _     <- Stream.resource(PubsubSubscriber.createSubscriber(projectId, subscription, config, queue))
      taken <- Stream.repeatEval(takeNextElements(queue))
      // Only retains the first error (if there are multiple), but that is OK, the stream is failing anyway...
      msg <- Stream.fromEither[F](taken.sequence).unchunks
    } yield msg
}

/** A wrapper that implements `java.util.Collection[A]` for `Builder[A, Vector[A]]`
  * so that we can pass the builder directly to the underlying library and
  * avoid copying
  */
private[consumer] class Wrapper[A](val underlying: Builder[A, Vector[A]]) extends util.Collection[A] {

  override def size(): Int = ???

  override def isEmpty(): Boolean = ???

  override def contains(x$1: Object): Boolean = ???

  override def iterator(): util.Iterator[A] = ???

  override def toArray(): Array[Object] = ???

  override def toArray[T <: Object](x$1: Array[T with Object]): Array[T with Object] = ???

  override def add(a: A): Boolean = {
    underlying += a
    true
  }

  override def remove(x$1: Object): Boolean = ???

  override def containsAll(x$1: util.Collection[_ <: Object]): Boolean = ???

  override def addAll(as: util.Collection[_ <: A]): Boolean = {
    underlying.addAll(as.asScala)
    true
  }

  override def removeAll(x$1: util.Collection[_ <: Object]): Boolean = ???

  override def retainAll(x$1: util.Collection[_ <: Object]): Boolean = ???

  override def clear(): Unit = ???

  def toChunk: Chunk[A] = Chunk.vector(underlying.result())

}

object Wrapper {
  def empty[A]: Wrapper[A] = new Wrapper(Vector.newBuilder[A])
}
