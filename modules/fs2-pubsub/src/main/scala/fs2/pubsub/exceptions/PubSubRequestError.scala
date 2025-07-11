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

package fs2.pubsub.exceptions

import cats.effect.Concurrent
import cats.syntax.all._

import org.http4s.Method
import org.http4s.Request
import org.http4s.Response
import org.http4s.Uri

/** Represents an error that occurred while making a request to Pub/Sub.
  *
  * @param message
  *   the error message
  * @param method
  *   the HTTP method used
  * @param uri
  *   the URI of the request
  * @param cause
  *   the cause of the error (optional)
  */
sealed abstract class PubSubRequestError private (
    val message: String,
    val method: Method,
    val uri: Uri,
    val cause: Option[Throwable]
) extends RuntimeException(
      s"""Request to PubSub failed.
         |
         |URI: $uri
         |Method: $method
         |
         |Failure: $message.""".stripMargin,
      cause.orNull
    )

object PubSubRequestError {

  /** Create a `PubSubRequestError` from a response and request.
    *
    * @param response
    *   the response
    * @param request
    *   the request
    * @return
    *   the error
    */
  def from[F[_]: Concurrent](response: Response[F], request: Request[F]) =
    response.bodyText.compile.string.redeem(_ => apply("Unknown error", request), PubSubRequestError(_, request))

  /** Create a `PubSubRequestError` from a message and request. */
  def apply[F[_]](message: String, request: Request[F]): PubSubRequestError =
    new PubSubRequestError(message.trim(), request.method, request.uri, None) {}

  /** Create a `PubSubRequestError` from a message, request, and cause. */
  def apply[F[_]](message: String, request: Request[F], cause: Throwable): PubSubRequestError =
    new PubSubRequestError(message.trim(), request.method, request.uri, cause.some) {}

}
