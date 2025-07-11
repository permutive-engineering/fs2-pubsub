/*
 * Copyright 2019-2025 Permutive Ltd. <https://permutive.com>
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

package fs2.pubsub.dsl

import scala.concurrent.duration._

import cats.effect.Temporal

import com.permutive.common.types.gcp.ProjectId
import fs2.pubsub.PubSubClient
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.middleware.RetryPolicy
import org.http4s.client.middleware.RetryPolicy.exponentialBackoff
import org.http4s.syntax.all._

object client {

  trait PubSubClientStep[F[_]] extends PubSubClient.Builder[F] {

    /** Create a `PubSubClient` from a configuration.
      *
      * @param config
      *   the configuration to use
      * @param F
      *   the effect type
      * @return
      *   a builder for the client
      */
    def fromConfig(config: PubSubClient.Config)(implicit F: Temporal[F]): PubSubClient.FromConfigBuilder[F] = {
      PubSubClient
        .http[F]
        .projectId(config.projectId)
        .uri(config.uri)
    }

  }

  trait ProjectIdStep[A] {

    /** Sets the GCP project ID.
      *
      * @param projectId
      *   the GCP project ID
      * @return
      *   the next step in the builder
      */
    def projectId(projectId: ProjectId): A

  }

  trait UriStep[A] {

    /** Sets the Pub/Sub URI.
      *
      * @param uri
      *   the Pub/Sub URI
      * @return
      *   the next step in the builder
      */
    def uri(uri: Uri): A

    /** Configures the builder to use the default Pub/Sub URI.
      *
      * @return
      *   the next step in the builder
      */
    def defaultUri: A = uri(uri"https://pubsub.googleapis.com")

  }

  trait ClientStep[F[_], A] {

    /** Sets the HTTP client to use. If the API requires authentication, the client should be configured with the
      * necessary credentials. You can use `permutive-engineering/gcp-auth` to create an authenticated client.
      *
      * @param client
      *   the HTTP client
      * @return
      *   the next step in the builder
      */
    def httpClient(client: Client[F]): A

  }

  trait RetryPolicyStep[F[_], A] {

    /** Sets the retry policy to use for the client.
      *
      * @param retryPolicy
      *   the retry policy
      * @return
      *   the next step in the builder
      */
    def retryPolicy(retryPolicy: RetryPolicy[F]): A

    /** Configures the builder to not retry requests.
      *
      * @return
      *   the next step in the builder
      */
    def noRetry: A = retryPolicy((_, _, _) => None)

    /** Configures the builder to retry requests recklessly. This will retry requests with exponential backoff of 5
      * seconds (up to a maximum of 3 requests).
      *
      * @return
      *   the next step in the builder
      */
    def defaultRetry: A = retryPolicy(RetryPolicy(exponentialBackoff(5.seconds, maxRetry = 3)))

  }

}
