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
import cats.effect.kernel.Resource
import cats.syntax.all._

import com.dimafeng.testcontainers.GenericContainer
import com.permutive.common.types.gcp.http4s._
import fs2.Chunk
import fs2.pubsub.dsl.client.PubSubClientStep
import io.circe.Json
import io.circe.syntax._
import munit.CatsEffectSuite
import org.http4s.Method._
import org.http4s.Uri
import org.http4s.circe._
import org.http4s.client.dsl.io._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.grpc.GrpcStatusCode
import org.http4s.grpc.GrpcStatusException
import org.testcontainers.containers.wait.strategy.Wait

class PubSubSuite extends CatsEffectSuite {

  override def munitIOTimeout: Duration = 2.minutes

  val options = List(
    ("gRPC", PubSubClient.grpc[IO]),
    ("HTTP", PubSubClient.http[IO])
  )

  options.foreach { case (clientType, constructor) =>
    afterProducing(constructor, records = 1)
      .test(s"$clientType - it should send and receive a message, acknowledging as expected") { subscriber =>
        val result = subscriber
          .evalTap(_.ack)
          .map(_.value)
          .interruptAfter(2.seconds)
          .compile
          .toList

        val expected = List("ping".some)

        assertIO(result, expected)
      }

    afterProducing(constructor, records = 5)
      .test(s"$clientType - it should preserve chunksize in the underlying stream") { subscriber =>
        val result = subscriber.chunks
          .evalTap(_.traverse(_.ack))
          .interruptAfter(2.seconds)
          .map(_.map(_.value))
          .compile
          .toList

        assertIO(result, List(Chunk("ping".some, "ping".some, "ping".some, "ping".some, "ping".some)))
      }

    afterProducing(constructor, records = 1, withAckDeadlineSeconds = 2)
      .test(s"$clientType - it should extend the deadline for a message") { subscriber =>
        val deadline = AckDeadline.from(10.seconds).toOption.get

        val result = subscriber
          .evalTap(_.extendDeadline(deadline))
          .evalTap(_ => IO.sleep(3.seconds))
          .evalTap(_.ack)
          .interruptAfter(5.seconds)
          .compile
          .count

        assertIO(result, 1L)
      }

    afterProducing(constructor, records = 1)
      .test(s"$clientType - it should nack a message properly") { subscriber =>
        val result = subscriber
          .evalScan(false) { case (nackedAlready, record) =>
            if (nackedAlready) record.ack.as(true) else record.nack.as(true)
          }
          .void
          .interruptAfter(2.seconds)
          .compile
          .count

        assertIO(result, 3L)
      }
  }

  options.foreach { case (clientType, constructor) =>
    withPubSubClient(constructor)
      .test(s"$clientType - checkTopic succeeds for an existing topic") { pubSubClient =>
        pubSubClient.checkTopic(Topic("example-topic"))
      }

    withPubSubClient(constructor)
      .test(s"$clientType - checkTopic fails for a non-existent topic") { pubSubClient =>
        interceptIO[Throwable](pubSubClient.checkTopic(Topic("nonexistent-topic")))
      }

    withPubSubClient(constructor)
      .test(s"$clientType - checkSubscription succeeds for an existing subscription") { pubSubClient =>
        pubSubClient.checkSubscription(Subscription("example-subscription"))
      }

    withPubSubClient(constructor)
      .test(s"$clientType - checkSubscription fails for a non-existent subscription") { pubSubClient =>
        interceptIO[Throwable](pubSubClient.checkSubscription(Subscription("nonexistent-subscription")))
      }

    withPubSubClient(constructor)
      .test(s"$clientType - async publisher Resource fails to allocate for a non-existent topic") { pubSubClient =>
        val resource = Resource
          .eval(pubSubClient.publisher[String].topic(Topic("nonexistent-topic")))
          .flatMap(_.batching.batchSize(10).maxLatency(1.second))

        interceptIO[Throwable](resource.use_.timeout(5.seconds))
      }

    withPubSubClient(constructor)
      .test(s"$clientType - subscriber stream fails for a non-existent subscription") { pubSubClient =>
        val stream = pubSubClient.subscriber
          .subscription(Subscription("nonexistent-subscription"))
          .noErrorHandling
          .withDefaults
          .raw

        interceptIO[Throwable](stream.compile.drain.timeout(5.seconds))
      }
  }

  withPubSubClient(PubSubClient.grpc[IO])
    .test("gRPC - checkTopic decodes the gRPC status of a non-existent topic") { pubSubClient =>
      interceptIO[GrpcStatusException](pubSubClient.checkTopic(Topic("nonexistent-topic")))
        .map(_.status.code)
        .assertEquals(GrpcStatusCode.NotFound)
    }

  //////////////
  // Fixtures //
  //////////////

  def withPubSubClient(constructor: PubSubClientStep[IO]) =
    ResourceFunFixture {
      val projectId = ProjectId("test-project")

      Resource.fromAutoCloseable(IO(container).flatTap(container => IO(container.start()))) >>
        EmberClientBuilder
          .default[IO]
          .withHttp2
          .build
          .evalTap { client =>
            val body = Json.obj(
              "topic"              := "projects/test-project/topics/example-topic",
              "ackDeadlineSeconds" := 10
            )

            val requests = List(
              PUT(container.uri / "v1" / "projects" / projectId / "topics" / "example-topic"),
              PUT(body, container.uri / "v1" / "projects" / projectId / "subscriptions" / "example-subscription")
            )

            requests.traverse_(client.expect[Unit])
          }
          .map { client =>
            constructor
              .projectId(projectId)
              .uri(container.uri)
              .httpClient(client)
              .noRetry
          }
    }

  def afterProducing(constructor: PubSubClientStep[IO], records: Int, withAckDeadlineSeconds: Int = 10) =
    ResourceFunFixture {
      val projectId = ProjectId("test-project")

      Resource.fromAutoCloseable(IO(container).flatTap(container => IO(container.start()))) >>
        EmberClientBuilder
          .default[IO]
          .withHttp2
          .build
          .evalTap { client =>
            val body = Json.obj(
              "topic"              := "projects/test-project/topics/example-topic",
              "ackDeadlineSeconds" := withAckDeadlineSeconds
            )

            val requests = List(
              PUT(container.uri / "v1" / "projects" / projectId / "topics" / "example-topic"),
              PUT(body, container.uri / "v1" / "projects" / projectId / "subscriptions" / "example-subscription")
            )

            requests.traverse_(client.expect[Unit])
          }
          .evalMap { client =>
            val pubSubClient = constructor
              .projectId(projectId)
              .uri(container.uri)
              .httpClient(client)
              .noRetry

            pubSubClient
              .publisher[String]
              .topic(Topic("example-topic"))
              .map { publisher =>
                val subscriber = pubSubClient.subscriber
                  .subscription(Subscription("example-subscription"))
                  .errorHandler {
                    case (PubSubSubscriber.Operation.Ack(_), t)         => IO.println(t)
                    case (PubSubSubscriber.Operation.Nack(_), t)        => IO.println(t)
                    case (PubSubSubscriber.Operation.Decode(record), t) => IO.println(t) >> record.ack
                  }
                  .withDefaults
                  .decodeTo[String]
                  .subscribe

                (publisher, subscriber)
              }
          }
          .evalTap {
            case (publisher, _) if records === 1 => publisher.publishOne("ping")
            case (publisher, _)                  => publisher.publishMany(List.fill(records)(PubSubRecord.Publisher("ping")))
          }
          ._2F
    }

  case object container
      extends GenericContainer(
        "google/cloud-sdk:emulators",
        command = "gcloud" :: "beta" :: "emulators" :: "pubsub" :: "start" :: "--project=test-project"
          :: "--host-port=0.0.0.0:8085" :: Nil,
        exposedPorts = Seq(8085),
        waitStrategy = Wait.forLogMessage(".*Server started, listening on 8085.*", 1).some
      ) {

    def uri = Uri.unsafeFromString(s"http://localhost:${mappedPort(8085)}")

  }

}
