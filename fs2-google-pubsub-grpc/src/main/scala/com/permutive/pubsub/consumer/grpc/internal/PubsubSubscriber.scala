package com.permutive.pubsub.consumer.grpc.internal

import cats.effect.{Blocker, ContextShift, Resource, Sync}
import cats.Applicative
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

import scala.collection.JavaConverters._

import java.util
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue, TimeUnit}

private[consumer] object PubsubSubscriber {

  def createSubscriber[F[_]: Sync: ContextShift](
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
    queue: BlockingQueue[Either[InternalPubSubError, Model.Record[F]]],
    blocker: Blocker,
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
      blocker
        .delay(service.stopAsync().awaitTerminated(config.awaitTerminatePeriod.toSeconds, TimeUnit.SECONDS))
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

  def takeNextElements[F[_]: Sync: ContextShift, A](messages: BlockingQueue[A], blocker: Blocker): F[Chunk[A]] =
    for {
      nextOpt <- Sync[F].delay(messages.poll()) // `poll` is non-blocking, returning `null` if queue is empty
      // `take` can wait for an element
      next <- if (nextOpt == null) blocker.delay(messages.take()) else Applicative[F].pure(nextOpt)
      chunk <- Sync[F].delay {
        val elements = new util.ArrayList[A]
        elements.add(next)
        messages.drainTo(elements)

        Chunk.buffer(elements.asScala)
      }
    } yield chunk

  def subscribe[F[_]: Sync: ContextShift](
    blocker: Blocker,
    projectId: PublicModel.ProjectId,
    subscription: PublicModel.Subscription,
    config: PubsubGoogleConsumerConfig[F],
  ): Stream[F, Model.Record[F]] =
    for {
      queue <- Stream.eval(
        Sync[F].delay(new LinkedBlockingQueue[Either[InternalPubSubError, Model.Record[F]]](config.maxQueueSize))
      )
      _     <- Stream.resource(PubsubSubscriber.createSubscriber(projectId, subscription, config, queue, blocker))
      taken <- Stream.repeatEval(takeNextElements(queue, blocker))
      // Only retains the first error (if there are multiple), but that is OK, the stream is failing anyway...
      msg <- Stream.fromEither[F](taken.sequence).flatMap(Stream.chunk)
    } yield msg
}
