package com.permutive.pubsub.producer.http.internal

import cats.effect.syntax.all._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.BatchingHttpProducerConfig
import com.permutive.pubsub.producer.http.BatchingHttpPubsubProducer.Batch
import com.permutive.pubsub.producer.{Model, PubsubProducer}
import fs2.Stream
import fs2.concurrent.Queue

private[http] class BatchingHttpPublisher[F[_] : Concurrent : Timer, A: MessageEncoder](
  queue: Queue[F, Model.Record[A]],
) extends PubsubProducer[F, A] {
  override def produce(record: A, metadata: Map[String, String], uniqueId: String): F[String] = {
    // TODO: what to return?
    queue.enqueue1(Model.Record(record, metadata, uniqueId)).map(_ => "Done")
  }

  override def produce(records: List[Model.Record[A]]): F[List[String]] = {
    import cats.instances.list._
    // TODO: what to return?
    records.traverse(queue.enqueue1).map(_ => records.map(_ => "Done"))
  }
}

object BatchingHttpPublisher {
  def resource[F[_] : Concurrent : Timer, A: MessageEncoder](
    publisher: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig,
    onPublishFailure: (Batch[A], Throwable) => F[Unit],
  ): Resource[F, PubsubProducer[F, A]] = {
    for {
      queue <- Resource.liftF(Queue.unbounded[F, Model.Record[A]])
      _ <- Resource.make(consume(publisher, config, queue, onPublishFailure).start)(_.cancel)
    } yield new BatchingHttpPublisher(queue)
  }

  private def consume[F[_] : Concurrent : Timer, A: MessageEncoder](
    underlying: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig,
    queue: Queue[F, Model.Record[A]],
    onPublishFailure: (Batch[A], Throwable) => F[Unit],
  ): F[Unit] = {
    val handler: List[Model.Record[A]] => F[List[String]] =
      if (config.retryTimes == 0) records => underlying.produce(records)
      else records => {
        Stream.retry(
          underlying.produce(records),
          delay = config.retryInitialDelay,
          nextDelay = config.retryNextDelay,
          maxAttempts = config.retryTimes,
        ).compile.lastOrError
      }

    queue
      .dequeue
      .groupWithin(config.batchSize, config.maxLatency)
      .evalMap { records =>
        val batch = records.toList
        handler(batch).handleErrorWith(onPublishFailure(batch, _).map(_ => Nil))
      }
      .compile
      .drain
  }
}
