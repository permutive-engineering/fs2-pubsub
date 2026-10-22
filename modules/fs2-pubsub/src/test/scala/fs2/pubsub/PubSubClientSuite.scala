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

import cats.effect.IO

import fs2.pubsub.exceptions.PubSubRequestError
import io.circe.Json
import io.circe.syntax._
import munit.CatsEffectSuite
import org.http4s.HttpApp
import org.http4s.HttpRoutes
import org.http4s.Response
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.syntax.all._

class PubSubClientSuite extends CatsEffectSuite {

  test("PubSubClient can be created from configuration class") {
    val config = PubSubClient.Config(
      projectId = ProjectId.random(),
      uri = uri"localhost:8080"
    )

    val client: Client[IO] = Client.fromHttpApp(HttpApp.notFound[IO])

    val publisher = PubSubClient
      .http[IO]
      .fromConfig(config)
      .httpClient(client)
      .noRetry

    assert(publisher.isInstanceOf[PubSubClient[IO]])
  }

  test("HTTP - checkTopic succeeds when testIamPermissions grants pubsub.topics.publish") {
    respondingToTestIamPermissions(Ok(Json.obj("permissions" := List("pubsub.topics.publish"))))
      .checkTopic(Topic("existing-topic"))
  }

  test("HTTP - checkTopic fails when testIamPermissions grants nothing") {
    interceptIO[PubSubRequestError] {
      respondingToTestIamPermissions(Ok(Json.obj())).checkTopic(Topic("existing-topic"))
    }.map(_.message).assertEquals("Missing permission pubsub.topics.publish")
  }

  test("HTTP - checkTopic checks the topic exists when testIamPermissions is not implemented") {
    val client = respondingToTestIamPermissions(NotImplemented())

    client.checkTopic(Topic("existing-topic")) >>
      interceptIO[PubSubRequestError](client.checkTopic(Topic("missing-topic"))).void
  }

  def respondingToTestIamPermissions(response: IO[Response[IO]]): PubSubClient[IO] = {
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "v1" / "projects" / _ / "topics" / name if name.endsWith(":testIamPermissions") => response
      case GET -> Root / "v1" / "projects" / _ / "topics" / "existing-topic"                              => Ok()
    }

    PubSubClient
      .http[IO]
      .projectId(ProjectId("test-project"))
      .uri(uri"http://localhost")
      .httpClient(Client.fromHttpApp(routes.orNotFound))
      .noRetry
  }

}
