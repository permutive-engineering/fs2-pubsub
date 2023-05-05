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

package com.permutive.pubsub

import cats.effect._
import cats.syntax.all._
import com.google.cloud.pubsub.v1.{SubscriptionAdminClient, TopicAdminClient}
import com.google.pubsub.v1.{SubscriptionName, TopicName}
import com.permutive.pubsub.consumer.ConsumerRecord
import com.permutive.pubsub.consumer.http.Example.ValueHolder
import com.permutive.pubsub.producer.Model.SimpleRecord
import com.permutive.pubsub.producer.{Model, PubsubProducer}
import fs2.Stream
import org.http4s.client.Client
import org.scalatest.BeforeAndAfterEach
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._

class HttpPingPongSpec extends PubSubSpec with BeforeAndAfterEach {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  // Delete topic and subscriptions after each test to ensure state is clean
  override def afterEach(): Unit =
    clearTopicSubscription.unsafeRunSync()

  private[this] val topicAndSubscriptionClient: Resource[IO, (TopicAdminClient, SubscriptionAdminClient)] =
    for {
      transCreds         <- providers
      topicClient        <- topicAdminClient(transCreds._1, transCreds._2)
      subscriptionClient <- subscriptionAdminClient(transCreds._1, transCreds._2)
    } yield (topicClient, subscriptionClient)

  private[this] val clearTopicSubscription: IO[Unit] =
    topicAndSubscriptionClient.use { case (topicClient, subscriptionClient) =>
      for {
        _ <- deleteSubscription(subscriptionClient, SubscriptionName.of(project, subscription))
        _ <- deleteTopic(topicClient, TopicName.of(project, topic))
      } yield ()
    }

  private def setup(ackDeadlineSeconds: Int): Resource[IO, (Client[IO], PubsubProducer[IO, ValueHolder])] =
    for {
      c <- client
      _ <- Resource.eval(createTopic(project, topic))
      _ <- Resource.eval(createSubscription(project, topic, subscription, ackDeadlineSeconds))
      p <- producer(c)
    } yield (c, p)

  private def consumeExpectingLimitedMessages(
    client: Client[IO],
    messagesExpected: Int,
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumer(client)
      // Check we only receive a single element
      .zipWithIndex
      .flatMap { case (record, ix) =>
        Stream.fromEither[IO](
          // Index is 0-based, so we expected index to reach 1 less than `messagesExpected`
          if (ix < messagesExpected.toLong) Right(record)
          else Left(new RuntimeException(s"Received more than $messagesExpected from PubSub"))
        )
      }
      // Check body is as we expect
      .flatMap(record =>
        Stream.fromEither[IO](
          if (record.value == ValueHolder("ping")) Right(record)
          else Left(new RuntimeException(s"Consumed element did not have correct value: ${record.value}"))
        )
      )

  private def consumeAndAck(
    client: Client[IO],
    elementsReceived: Ref[IO, Int],
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumeExpectingLimitedMessages(client, messagesExpected = 1)
      // Indicate we have received the element as expected
      .evalTap(_ => elementsReceived.update(_ + 1))
      .evalTap(_.ack)

  it should "send and receive a message, acknowledging as expected" in {
    (for {
      // We will sleep for 10 seconds, which means if the message is not acked it will be redelivered before end of test
      (client, producer) <- Stream.resource(setup(ackDeadlineSeconds = 5))
      _                  <- Stream.eval(producer.produce(ValueHolder("ping")))
      ref                <- Stream.eval(Ref.of[IO, Int](0))
      // Wait 10 seconds whilst we run the consumer to check we have a single element and it has the right data
      _                <- Stream.sleep[IO](10.seconds).concurrently(consumeAndAck(client, ref))
      elementsReceived <- Stream.eval(ref.get)
    } yield elementsReceived should ===(1))
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

  private def consumeExpectingChunksize(
    client: Client[IO],
    elementsReceived: Ref[IO, Int],
    chunkSizeExpected: Int,
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumer(client).chunks
      .evalTap(c =>
        IO.raiseError(new RuntimeException(s"Chunks were of the wrong size, received ${c.size}"))
          .unlessA(c.size == chunkSizeExpected)
      )
      .unchunks
      .evalTap(_ => elementsReceived.update(_ + 1))
      .evalTap(_.ack)

  it should "preserve chunksize in the underlying stream" in {
    val messagesToSend = 5

    (for {
      // We will sleep for 10 seconds, which means if the message is not acked it will be redelivered before end of test
      (client, producer) <- Stream.resource(setup(ackDeadlineSeconds = 5))
      _ <- Stream.eval(
        // This must be produced using `produceMany` otherwise the returned elements are in individual chunks
        producer.produceMany(List.fill[Model.Record[ValueHolder]](messagesToSend)(SimpleRecord(ValueHolder("ping"))))
      )
      ref <- Stream.eval(Ref.of[IO, Int](0))
      // Wait 10 seconds whilst we run the consumer to check we have received all elements in a single chunk
      _                <- Stream.sleep[IO](10.seconds).concurrently(consumeExpectingChunksize(client, ref, messagesToSend))
      elementsReceived <- Stream.eval(ref.get)
    } yield elementsReceived should ===(messagesToSend))
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

  private def consumeExtendSleepAck(
    client: Client[IO],
    elementsReceived: Ref[IO, Int],
    extendDuration: FiniteDuration,
    sleepDuration: FiniteDuration,
  ): Stream[IO, ConsumerRecord[IO, ValueHolder]] =
    consumeExpectingLimitedMessages(client, messagesExpected = 1)
      .evalTap(_.extendDeadline(extendDuration))
      .evalTap(_ => IO.sleep(sleepDuration))
      // Indicate we have received the element as expected
      .evalTap(_ => elementsReceived.update(_ + 1))
      .evalTap(_.ack)

  it should "extend the deadline for a message" in {
    // These setting mean that if extension does not work the message will be redelivered before the end of the test
    val ackDeadlineSeconds = 2
    val sleepDuration      = 3.seconds
    val extendDuration     = 10.seconds

    (for {
      (client, producer) <- Stream.resource(setup(ackDeadlineSeconds = ackDeadlineSeconds))
      _                  <- Stream.eval(producer.produce(ValueHolder("ping")))
      ref                <- Stream.eval(Ref.of[IO, Int](0))
      // Wait 10 seconds whilst we run the consumer to check we have a single element and it has the right data
      _ <- Stream
        .sleep[IO](10.seconds)
        .concurrently(
          consumeExtendSleepAck(client, ref, extendDuration = extendDuration, sleepDuration = sleepDuration)
        )
      elementsReceived <- Stream.eval(ref.get)
    } yield elementsReceived should ===(1))
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

  private def consumeNackThenAck(
    client: Client[IO],
    elementsReceived: Ref[IO, Int],
    messagesExpected: Int,
  ): Stream[IO, Unit] =
    // We expect the message to be redelivered once, so expect 2 messages total
    consumeExpectingLimitedMessages(client, messagesExpected = messagesExpected)
      // Indicate we have received the element as expected
      .evalTap(_ => elementsReceived.update(_ + 1))
      // Nack the first message, then ack subsequent ones
      .evalScan(false) { case (nackedAlready, record) =>
        if (nackedAlready) record.ack.as(true) else record.nack.as(true)
      }
      .void

  it should "nack a message properly" in {
    // These setting mean that a message will only be redelivered if it is nacked
    val ackDeadlineSeconds = 100
    val messagesExpected   = 2

    (for {
      (client, producer) <- Stream.resource(setup(ackDeadlineSeconds = ackDeadlineSeconds))
      _                  <- Stream.eval(producer.produce(ValueHolder("ping")))
      ref                <- Stream.eval(Ref.of[IO, Int](0))
      // Wait 10 seconds whilst we run the consumer which nacks the message, then acks
      _ <- Stream
        .sleep[IO](10.seconds)
        .concurrently(consumeNackThenAck(client, ref, messagesExpected))
      elementsReceived <- Stream.eval(ref.get)
    } yield elementsReceived should ===(messagesExpected))
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

}
