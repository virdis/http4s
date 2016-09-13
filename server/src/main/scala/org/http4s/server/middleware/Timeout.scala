package org.http4s
package server
package middleware

import java.util.concurrent._
import scala.concurrent._
import scala.concurrent.duration._

import fs2._
import org.http4s.batteries._

object Timeout {

  // TODO: this should probably be replaced with a low res executor to save clock cycles
  private val ec = new ScheduledThreadPoolExecutor(1)

  private implicit val strategy = Strategy.fromExecutionContext(
    ExecutionContext.fromExecutor(ec))

  val DefaultTimeoutResponse = Response(Status.InternalServerError)
    .withBody("The service timed out.")

  private def timeoutResp(timeout: Duration, response: Task[Response]): Task[Response] = Task.async[Task[Response]] { cb =>
    val r = new Runnable { override def run(): Unit = cb(Right(response)) }
    ec.schedule(r, timeout.toNanos, TimeUnit.NANOSECONDS)
    ()
  }.flatten

  /** Transform the service such to return whichever resolves first:
    * the provided Task[Response], or the result of the service
    * @param timeoutResponse Task[Response] to race against the result of the service. This will be run for each [[Request]]
    * @param service [[org.http4s.server.HttpService]] to transform
    */
  def apply(timeoutResponse: Task[Response])(service: HttpService): HttpService =
    service.mapF { resp =>
      (resp race timeoutResponse).map(_.merge)
    }

  /** Transform the service to return a RequestTimeOut [[Status]] after the supplied Duration
    * @param timeout Duration to wait before returning the RequestTimeOut
    * @param service [[HttpService]] to transform
    */
  def apply(timeout: Duration, response: Task[Response] = DefaultTimeoutResponse)(service: HttpService): HttpService = {
    if (timeout.isFinite()) apply(timeoutResp(timeout, response))(service)
    else service
  }

  /** Transform the service to return a RequestTimeOut [[Status]] after 30 seconds
    * @param service [[HttpService]] to transform
    */
  def apply(service: HttpService): HttpService = apply(30.seconds)(service)
}
