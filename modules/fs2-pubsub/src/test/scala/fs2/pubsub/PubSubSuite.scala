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
import org.http4s.client.Client
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

        assertIO(result.timeoutAndForget(30.seconds), expected)
      }

    afterProducing(constructor, records = 5)
      .test(s"$clientType - it should preserve chunksize in the underlying stream") { subscriber =>
        val result = subscriber.chunks
          .evalTap(_.traverse(_.ack))
          .interruptAfter(2.seconds)
          .map(_.map(_.value))
          .compile
          .toList

        assertIO(
          result.timeoutAndForget(30.seconds),
          List(Chunk("ping".some, "ping".some, "ping".some, "ping".some, "ping".some))
        )
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

        assertIO(result.timeoutAndForget(30.seconds), 1L)
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

        assertIO(result.timeoutAndForget(30.seconds), 3L)
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
    RetriedFixture {
      val projectId = ProjectId("test-project")

      Resource.fromAutoCloseable(IO(container).flatTap(container => IO(container.start()))) >>
        h2Client(projectId).evalTap { client =>
          val body = Json.obj(
            "topic"              := "projects/test-project/topics/example-topic",
            "ackDeadlineSeconds" := 10
          )

          val requests = List(
            PUT(container.uri / "v1" / "projects" / projectId / "topics" / "example-topic"),
            PUT(body, container.uri / "v1" / "projects" / projectId / "subscriptions" / "example-subscription")
          )

          requests.traverse_(client.expect[Unit]).timeout(30.seconds)
        }.map { client =>
          constructor
            .projectId(projectId)
            .uri(container.uri)
            .httpClient(client)
            .noRetry
        }
    }

  def afterProducing(constructor: PubSubClientStep[IO], records: Int, withAckDeadlineSeconds: Int = 10) =
    RetriedFixture {
      val projectId = ProjectId("test-project")

      Resource.fromAutoCloseable(IO(container).flatTap(container => IO(container.start()))) >>
        h2Client(projectId).evalTap { client =>
          val body = Json.obj(
            "topic"              := "projects/test-project/topics/example-topic",
            "ackDeadlineSeconds" := withAckDeadlineSeconds
          )

          val requests = List(
            PUT(container.uri / "v1" / "projects" / projectId / "topics" / "example-topic"),
            PUT(body, container.uri / "v1" / "projects" / projectId / "subscriptions" / "example-subscription")
          )

          requests.traverse_(client.expect[Unit]).timeout(30.seconds)
        }.evalMap { client =>
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
        }.evalTap {
          case (publisher, _) if records === 1 => publisher.publishOne("ping").timeout(30.seconds)
          case (publisher, _)                  =>
            publisher.publishMany(List.fill(records)(PubSubRecord.Publisher("ping"))).timeout(30.seconds)
        }._2F
    }

  // ember's h2 client can write its SETTINGS ACK before its own SETTINGS and the peer then
  // rejects or silently drops the connection. The failure escapes through the client's own
  // Resource scope, so nothing inside it can retry; RetriedFixture re-runs the whole test body
  // with a fresh client and emulator instead. The probe only turns a silent hang into a fast
  // failure.
  private def h2Client(projectId: ProjectId): Resource[IO, Client[IO]] =
    EmberClientBuilder
      .default[IO]
      .withHttp2
      .build
      .evalTap { client =>
        client
          .expect[Unit](GET(container.uri / "v1" / "projects" / projectId / "topics"))
          .timeout(10.seconds)
          .start
          .flatMap(_.joinWith(IO.raiseError(new IllegalStateException("h2 connection cancelled during probe"))))
      }

  final class RetriedFixture[A](resource: Resource[IO, A]) {

    def test(name: String)(body: A => IO[Any])(implicit loc: munit.Location): Unit =
      PubSubSuite.this.test(name)(retry(3)(resource.use(body)))

    private def retry(attempts: Int)(io: IO[Any]): IO[Any] =
      io.handleErrorWith {
        case failure: AssertionError => IO.raiseError(failure)
        case error if attempts > 1   => IO.println(s"Retrying after: $error") >> retry(attempts - 1)(io)
        case error                   => IO.raiseError(error)
      }

  }

  object RetriedFixture {

    def apply[A](resource: Resource[IO, A]): RetriedFixture[A] = new RetriedFixture(resource)

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
