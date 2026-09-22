/*
 * Copyright 2019-2026 Permutive Ltd. <https://permutive.com>
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

package fs2.pubsub

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all._

import fs2.Chunk
import fs2.Stream
import fs2.pubsub.dsl.subscriber.SubscribeStep
import munit.FunSuite
import org.http4s.HttpApp
import org.http4s.client.Client
import org.http4s.syntax.all._

class PubSubSubscriberSuite extends FunSuite {

  test("PubSubSubscriber can be created from configuration class") {
    val config = PubSubSubscriber.Config(
      projectId = ProjectId.random(),
      subscription = Subscription("my-subscription"),
      uri = uri"localhost:8080",
      batchSize = 10,
      maxLatency = 1.second,
      readMaxMessages = 100,
      readConcurrency = 3
    )

    val client: Client[IO] = Client.fromHttpApp(HttpApp.notFound[IO])

    val subscriber = PubSubSubscriber
      .http[IO]
      .fromConfig(config)
      .httpClient(client)
      .noRetry
      .noErrorHandling
      .raw

    assert(subscriber.isInstanceOf[Stream[IO, PubSubRecord.Subscriber[IO, Array[Byte]]]])
  }

  test("subscribeAndEnsurePayload keeps the chunks of the underlying stream") {
    val result = for {
      acked  <- Ref.of[IO, List[String]](Nil)
      records = List("a".some, none, "c".some, "d".some, "e".some).zipWithIndex.map { case (value, index) =>
                  PubSubRecord.Subscriber[IO, String](
                    value,
                    Map.empty,
                    None,
                    None,
                    None,
                    AckId(s"ack-$index"),
                    acked.update(s"ack-$index" :: _),
                    IO.unit,
                    _ => IO.unit
                  )
                }
      stream = Stream.chunk(Chunk.from(records.take(3))) ++ Stream.chunk(Chunk.from(records.drop(3)))
      sizes <- SubscribeStep(stream).subscribeAndEnsurePayload.chunks.map(_.size).compile.toList
      acks  <- acked.get
    } yield (sizes, acks)

    assertEquals(result.unsafeRunSync(), (List(2, 2), List("ack-1")))
  }

}
