/*
 * Copyright 2019-2024 Permutive Ltd. <https://permutive.com>
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
import cats.effect.std.Queue
import cats.syntax.all._

import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.munit.fixtures.TestContainersFixtures
import com.permutive.common.types.gcp.http4s._
import fs2.Chunk
import io.circe.Json
import io.circe.syntax._
import munit.CatsEffectSuite
import org.http4s.Method._
import org.http4s.Uri
import org.http4s.circe._
import org.http4s.client.dsl.io._
import org.http4s.ember.client.EmberClientBuilder
import org.testcontainers.containers.wait.strategy.Wait

class PubSubSuite extends CatsEffectSuite with TestContainersFixtures {

  afterProducing(records = 1)
    .test("it should send and receive a message, acknowledging as expected") { subscriber =>
      val result = subscriber
        .evalTap(_.ack)
        .map(_.value)
        .interruptAfter(2.seconds)
        .compile
        .toList

      val expected = List("ping".some)

      assertIO(result, expected)
    }

  afterProducing(records = 5)
    .test("it should preserve chunksize in the underlying stream") { subscriber =>
      val result = subscriber.chunks
        .evalTap(_.traverse(_.ack))
        .interruptAfter(2.seconds)
        .map(_.map(_.value))
        .compile
        .toList

      assertIO(result, List(Chunk("ping".some, "ping".some, "ping".some, "ping".some, "ping".some)))
    }

  afterProducing(records = 1, withAckDeadlineSeconds = 2)
    .test("it should extend the deadline for a message") { subscriber =>
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

  afterProducing(records = 1)
    .test("it should nack a message properly") { subscriber =>
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

  //////////////
  // Fixtures //
  //////////////

  val projects = List.fill(4)("example-topic:example-subscription").zipWithIndex.map { case (topics, index) =>
    s"test-project-${index + 1},$topics"
  }

  val projectsFixture = ResourceSuiteLocalFixture(
    "Projects",
    Queue
      .unbounded[IO, ProjectId]
      .flatTap(queue => projects.map(_.split(",").head).traverse(ProjectId.fromStringF[IO](_).flatMap(queue.offer)))
      .toResource
  )

  def afterProducing(records: Int, withAckDeadlineSeconds: Int = 10) = ResourceFunFixture {
    IO(projectsFixture().take).flatten.toResource
      .product(EmberClientBuilder.default[IO].build)
      .evalTap { case (projectId, client) =>
        val body = Json.obj(
          "subscription" := Json.obj(
            "topic"              := "example-topic",
            "ackDeadlineSeconds" := withAckDeadlineSeconds
          ),
          "updateMask" := "ackDeadlineSeconds"
        )

        val request =
          PATCH(body, container.uri / "v1" / "projects" / projectId / "subscriptions" / "example-subscription")

        client.expect[Unit](request)
      }
      .map { case (projectId, client) =>
        val pubSubClient = PubSubClient
          .http[IO]
          .projectId(projectId)
          .uri(container.uri)
          .httpClient(client)
          .noRetry

        val publisher = pubSubClient
          .publisher[String]
          .topic(Topic("example-topic"))

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
      .evalTap {
        case (publisher, _) if records === 1 => publisher.publishOne("ping")
        case (publisher, _)                  => publisher.publishMany(List.fill(records)(PubSubRecord.Publisher("ping")))
      }
      ._2F
  }

  case object container
      extends GenericContainer(
        "thekevjames/gcloud-pubsub-emulator:450.0.0",
        exposedPorts = Seq(8681, 8682),
        waitStrategy = Wait.forListeningPort().some,
        env = projects.zipWithIndex.map { case (project, index) => s"PUBSUB_PROJECT${index + 1}" -> project }.toMap
      ) {

    def uri = Uri.unsafeFromString(s"http://localhost:${mappedPort(8681)}")

  }

  val containerFixture = new ForAllContainerFixture[GenericContainer](container)

  override def munitFixtures = super.munitFixtures :+ projectsFixture :+ containerFixture

}
